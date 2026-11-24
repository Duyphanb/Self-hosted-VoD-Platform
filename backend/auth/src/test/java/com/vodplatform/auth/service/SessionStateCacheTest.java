package com.vodplatform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vodplatform.auth.persistence.SessionState;
import com.vodplatform.auth.persistence.UserStatus;
import com.vodplatform.auth.security.AuthStateUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionStateCacheTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final Optional<SessionState> ACTIVE = Optional.of(new SessionState(
            UUID.randomUUID(), UserStatus.ACTIVE, 0, Instant.parse("2030-01-08T00:00:00Z"), null, null));

    private final SessionStateReader reader = mock(SessionStateReader.class);
    private final AtomicLong nanos = new AtomicLong();
    private final SessionStateCache cache = new SessionStateCache(reader, nanos::get, 20);

    @Test
    void thirtySecondsStartsBeforeTheReadAndHitsDoNotRenewIt() {
        when(reader.read(SESSION_ID, false)).thenAnswer(invocation -> {
            nanos.set(Duration.ofSeconds(20).toNanos());
            return ACTIVE;
        }).thenReturn(Optional.empty());

        assertThat(cache.read(SESSION_ID)).isEqualTo(ACTIVE);
        nanos.set(Duration.ofSeconds(30).toNanos() - 1);
        assertThat(cache.read(SESSION_ID)).isEqualTo(ACTIVE);
        nanos.incrementAndGet();
        assertThat(cache.read(SESSION_ID)).isEmpty();
        verify(reader, times(2)).read(SESSION_ID, false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentMissesShareTheReadAndInvalidationDiscardsItForBothCallers(boolean invalidate) throws Exception {
        var reading = new CountDownLatch(1);
        var returnSnapshot = new CountDownLatch(1);
        when(reader.read(SESSION_ID, false)).thenAnswer(invocation -> {
            reading.countDown();
            assertThat(returnSnapshot.await(10, TimeUnit.SECONDS)).isTrue();
            return ACTIVE;
        }).thenReturn(Optional.empty());
        var waiterThread = new AtomicReference<Thread>();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> cache.read(SESSION_ID));
            try {
                assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();
                var second = workers.submit(() -> {
                    waiterThread.set(Thread.currentThread());
                    return cache.read(SESSION_ID);
                });
                waitUntilJoining(waiterThread);
                if (invalidate) {
                    cache.invalidate(SESSION_ID);
                }
                returnSnapshot.countDown();
                Optional<SessionState> expected = invalidate ? Optional.empty() : ACTIVE;
                assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
                assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
                assertThat(cache.read(SESSION_ID)).isEqualTo(expected);
                verify(reader, times(invalidate ? 2 : 1)).read(SESSION_ID, false);
            } finally {
                returnSnapshot.countDown();
            }
        }
    }

    @Test
    void twoOverAgeLoadsFailAsUnavailableForBothTheLoaderAndTheWaitingCaller() throws Exception {
        var firstReadStarted = new CountDownLatch(1);
        var finishFirstRead = new CountDownLatch(1);
        var loads = new AtomicInteger();
        when(reader.read(SESSION_ID, false)).thenAnswer(invocation -> {
            int load = loads.incrementAndGet();
            if (load == 1) {
                firstReadStarted.countDown();
                assertThat(finishFirstRead.await(10, TimeUnit.SECONDS)).isTrue();
            }
            if (load <= 3) {
                nanos.addAndGet(Duration.ofSeconds(30).toNanos());
            }
            return ACTIVE;
        });
        var waiterThread = new AtomicReference<Thread>();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var loader = workers.submit(() -> cache.read(SESSION_ID));
            try {
                assertThat(firstReadStarted.await(10, TimeUnit.SECONDS)).isTrue();
                var waiter = workers.submit(() -> {
                    waiterThread.set(Thread.currentThread());
                    return cache.read(SESSION_ID);
                });
                waitUntilJoining(waiterThread);
                finishFirstRead.countDown();
                assertThatThrownBy(() -> loader.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(AuthStateUnavailableException.class);
                assertThatThrownBy(() -> waiter.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(AuthStateUnavailableException.class);
                verify(reader, atLeast(2)).read(SESSION_ID, false);
                verify(reader, atMost(3)).read(SESSION_ID, false);
            } finally {
                finishFirstRead.countDown();
            }
        }
    }

    @Test
    void aReadFailureIsSharedAsUnavailableAndAllowsTheNextLoad() {
        when(reader.read(SESSION_ID, false))
                .thenThrow(new AuthStateUnavailableException(new IllegalStateException("fixture")))
                .thenReturn(ACTIVE);

        assertThatThrownBy(() -> cache.read(SESSION_ID)).isInstanceOf(AuthStateUnavailableException.class);
        assertThat(cache.read(SESSION_ID)).isEqualTo(ACTIVE);
        verify(reader, times(2)).read(SESSION_ID, false);
    }

    private void waitUntilJoining(AtomicReference<Thread> waiter) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Thread thread = waiter.get();
            if (thread != null && thread.getState() == Thread.State.WAITING) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("The concurrent caller did not join the in-flight load");
    }
}
