package com.vodplatform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vodplatform.auth.persistence.AuthSessionRepository;
import com.vodplatform.auth.persistence.SessionState;
import com.vodplatform.auth.persistence.UserStatus;
import com.vodplatform.auth.security.AuthStateUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SessionStateReaderTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final Optional<SessionState> ACTIVE = Optional.of(new SessionState(
            UUID.randomUUID(), UserStatus.ACTIVE, 0, Instant.parse("2030-01-08T00:00:00Z"), null, null));

    private final AuthSessionRepository sessions = mock(AuthSessionRepository.class);
    private final ThreadPoolTaskExecutor executor = singleThreadExecutor();
    private final CountDownLatch releaseRead = new CountDownLatch(1);

    @AfterEach
    void stopExecutor() {
        releaseRead.countDown();
        executor.shutdown();
    }

    @Test
    void returnsTheStoredStateReadOnTheExecutor() {
        when(sessions.readState(SESSION_ID, true)).thenReturn(ACTIVE);
        var reader = new SessionStateReader(sessions, executor, Duration.ofSeconds(1));

        assertThat(reader.read(SESSION_ID, true)).isEqualTo(ACTIVE);
    }

    @Test
    void aStalledReadIsUnavailableOnceTheTimeoutPasses() {
        var reading = new CountDownLatch(1);
        when(sessions.readState(SESSION_ID, false)).thenAnswer(invocation -> {
            reading.countDown();
            assertThat(releaseRead.await(10, TimeUnit.SECONDS)).isTrue();
            return ACTIVE;
        });
        var reader = new SessionStateReader(sessions, executor, Duration.ofMillis(200));

        long started = System.nanoTime();
        assertThatThrownBy(() -> reader.read(SESSION_ID, false)).isInstanceOf(AuthStateUnavailableException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(reading.getCount()).isZero();
    }

    @Test
    void aReadQueuedBehindAStalledReadNeverRunsAfterItsCallerGaveUp() throws Exception {
        var reading = new CountDownLatch(1);
        when(sessions.readState(SESSION_ID, false)).thenAnswer(invocation -> {
            reading.countDown();
            assertThat(releaseRead.await(10, TimeUnit.SECONDS)).isTrue();
            return ACTIVE;
        });
        var reader = new SessionStateReader(sessions, executor, Duration.ofMillis(200));
        try (var callers = Executors.newSingleThreadExecutor()) {
            var stalled = callers.submit(() -> reader.read(SESSION_ID, false));
            assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> reader.read(SESSION_ID, true)).isInstanceOf(AuthStateUnavailableException.class);
            var sentinel = new CountDownLatch(1);
            executor.execute(sentinel::countDown);
            releaseRead.countDown();
            assertThatThrownBy(stalled::get).hasCauseInstanceOf(AuthStateUnavailableException.class);
            // The worker runs queued tasks in order, so the expired read had its turn before the sentinel.
            assertThat(sentinel.await(5, TimeUnit.SECONDS)).isTrue();
        }

        verify(sessions, times(0)).readState(SESSION_ID, true);
    }

    @Test
    void aSaturatedExecutorIsUnavailableAtOnce() {
        Executor saturated = task -> {
            throw new RejectedExecutionException("fixture");
        };
        var reader = new SessionStateReader(sessions, saturated, Duration.ofSeconds(1));

        assertThatThrownBy(() -> reader.read(SESSION_ID, false)).isInstanceOf(AuthStateUnavailableException.class);
    }

    @Test
    void aCallerInsideATransactionReadsOnItsOwnConnection() {
        when(sessions.readState(SESSION_ID, false)).thenReturn(ACTIVE);
        Executor unused = task -> {
            throw new AssertionError("A caller inside a transaction must not use the executor");
        };
        var reader = new SessionStateReader(sessions, unused, Duration.ofSeconds(1));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThat(reader.read(SESSION_ID, false)).isEqualTo(ACTIVE);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void aDatabaseFailureIsUnavailable() {
        when(sessions.readState(SESSION_ID, false)).thenThrow(new DataAccessResourceFailureException("fixture"));
        var reader = new SessionStateReader(sessions, executor, Duration.ofSeconds(1));

        assertThatThrownBy(() -> reader.read(SESSION_ID, false))
                .isInstanceOf(AuthStateUnavailableException.class)
                .hasCauseInstanceOf(DataAccessResourceFailureException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s", "0.0005s", "21s"})
    void rejectsATimeoutOutsideTheFreshnessWindow(String timeout) {
        Duration outOfRange = Duration.parse("PT" + timeout.toUpperCase());

        assertThatThrownBy(() -> new SessionStateReader(sessions, executor, outOfRange))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ThreadPoolTaskExecutor singleThreadExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.initialize();
        return executor;
    }
}
