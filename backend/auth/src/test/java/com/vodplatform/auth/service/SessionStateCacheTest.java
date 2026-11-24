package com.vodplatform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SessionStateCacheTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final Optional<SessionState> ACTIVE = Optional.of(new SessionState(
            UUID.randomUUID(), UserStatus.ACTIVE, 0, Instant.parse("2030-01-08T00:00:00Z"), null, null));

    private final SessionStateReader reader = mock(SessionStateReader.class);
    private final AtomicLong nanos = new AtomicLong();
    private final SessionStateCache cache = new SessionStateCache(reader, nanos::get, 20);

    // Physical reads run here, as on the reader's executor; each one calls the stubbed synchronous read.
    private final ExecutorService readThreads = Executors.newCachedThreadPool();

    @BeforeEach
    void stubTheAsynchronousRead() {
        when(reader.timeout()).thenReturn(Duration.ofSeconds(10));
        when(reader.readAsync(any(), anyBoolean())).thenAnswer(invocation -> CompletableFuture.supplyAsync(
                () -> reader.read(invocation.getArgument(0), invocation.getArgument(1)), readThreads));
    }

    @AfterEach
    void stopReadThreads() {
        readThreads.shutdownNow();
    }

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

    @Test
    void theStarterAndASharingCallerOfAStalledLoadGetUnavailableAfterTheReaderTimeout() throws Exception {
        when(reader.timeout()).thenReturn(Duration.ofMillis(200));
        var reading = new CountDownLatch(1);
        var releaseRead = new CountDownLatch(1);
        when(reader.read(SESSION_ID, false)).thenAnswer(invocation -> {
            reading.countDown();
            assertThat(releaseRead.await(10, TimeUnit.SECONDS)).isTrue();
            return ACTIVE;
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var loader = workers.submit(() -> cache.read(SESSION_ID));
            try {
                assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();
                long started = System.nanoTime();
                var waiter = workers.submit(() -> cache.read(SESSION_ID));

                assertThatThrownBy(() -> waiter.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(AuthStateUnavailableException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            } finally {
                releaseRead.countDown();
            }
            // The caller that started the read is bounded by the same timeout.
            assertThatThrownBy(() -> loader.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AuthStateUnavailableException.class);
        }
        verify(reader, times(1)).read(SESSION_ID, false);
    }

    @Test
    void theCacheKeepsAtMostItsCapacityOfDistinctSessions() {
        when(reader.read(any(), eq(false)))
                .thenReturn(ACTIVE);

        for (int session = 0; session < 50; session++) {
            assertThat(cache.read(UUID.randomUUID())).isEqualTo(ACTIVE);
        }

        assertThat(cache.cachedSessionCount()).isLessThanOrEqualTo(20);
    }

    @Test
    void aCallerInsideATransactionNeitherUsesNorFillsTheCache() {
        when(reader.read(SESSION_ID, false)).thenReturn(ACTIVE);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThat(cache.read(SESSION_ID)).isEqualTo(ACTIVE);
            assertThat(cache.read(SESSION_ID)).isEqualTo(ACTIVE);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        assertThat(cache.cachedSessionCount()).isZero();
        verify(reader, times(2)).read(SESSION_ID, false);
    }

    @Test
    void callersThatGiveUpOnAStalledReadShareItAndItsResultStillArrives() {
        when(reader.timeout()).thenReturn(Duration.ofMillis(100));
        var releaseRead = new CountDownLatch(1);
        when(reader.read(SESSION_ID, false)).thenAnswer(invocation -> {
            assertThat(releaseRead.await(10, TimeUnit.SECONDS)).isTrue();
            return ACTIVE;
        });

        for (int caller = 0; caller < 3; caller++) {
            assertThatThrownBy(() -> cache.read(SESSION_ID)).isInstanceOf(AuthStateUnavailableException.class);
        }
        verify(reader, times(1)).readAsync(SESSION_ID, false);

        releaseRead.countDown();
        when(reader.timeout()).thenReturn(Duration.ofSeconds(10));
        assertThat(cache.read(SESSION_ID)).isEqualTo(ACTIVE);
        verify(reader, times(1)).read(SESSION_ID, false);
    }

    @Test
    void aLoadLeavesTheMapBeforeItsCallersWakeSoARetryStartsANewRead() throws Exception {
        var reading = new CountDownLatch(1);
        var releaseRead = new CountDownLatch(1);
        when(reader.read(SESSION_ID, false)).thenAnswer(invocation -> {
            reading.countDown();
            assertThat(releaseRead.await(10, TimeUnit.SECONDS)).isTrue();
            return ACTIVE;
        }).thenReturn(ACTIVE);
        try (var callers = Executors.newSingleThreadExecutor()) {
            var caller = callers.submit(() -> cache.read(SESSION_ID));
            assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<?> originalLoad = cache.runningLoad(SESSION_ID);
            // The callback runs on the completing thread as the callers wake. A replacement load may already be
            // registered by then; only the original one must be gone.
            CompletableFuture<Boolean> detachedBeforeCompletion =
                    originalLoad.handle((result, failure) -> cache.runningLoad(SESSION_ID) != originalLoad);
            cache.invalidate(SESSION_ID);
            releaseRead.countDown();

            assertThat(caller.get(10, TimeUnit.SECONDS)).isEqualTo(ACTIVE);
            assertThat(detachedBeforeCompletion.get(10, TimeUnit.SECONDS)).isTrue();
        }
        verify(reader, times(2)).read(SESSION_ID, false);
    }

    @Test
    void aCallerInsideATransactionSeesItsOwnStateWhileOthersKeepTheCommittedSnapshot() {
        Optional<SessionState> revokedInTransaction = Optional.empty();
        when(reader.read(SESSION_ID, false)).thenReturn(ACTIVE).thenReturn(revokedInTransaction);
        assertThat(cache.read(SESSION_ID)).isEqualTo(ACTIVE);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThat(cache.read(SESSION_ID)).isEqualTo(revokedInTransaction);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        assertThat(cache.read(SESSION_ID)).isEqualTo(ACTIVE);
        verify(reader, times(2)).read(SESSION_ID, false);
    }

    @Test
    void aLoadRetiredUnreadInTheQueueLetsItsCallersReadAgain() {
        when(reader.read(SESSION_ID, false)).thenReturn(ACTIVE);
        when(reader.readAsync(SESSION_ID, false))
                .thenReturn(CompletableFuture.failedFuture(new SessionStateReader.RetiredUnreadException()))
                .thenAnswer(invocation -> CompletableFuture.supplyAsync(
                        () -> reader.read(SESSION_ID, false), readThreads));

        assertThat(cache.read(SESSION_ID)).isEqualTo(ACTIVE);
        verify(reader, times(2)).readAsync(SESSION_ID, false);
    }

    private void waitUntilJoining(AtomicReference<Thread> waiter) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Thread thread = waiter.get();
            if (thread != null && thread.getState() == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("The concurrent caller did not join the in-flight load");
    }
}
