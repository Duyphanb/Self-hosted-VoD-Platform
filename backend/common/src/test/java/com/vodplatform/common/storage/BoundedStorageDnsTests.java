package com.vodplatform.common.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.minio.MinioClient;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

class BoundedStorageDnsTests {
    @Test
    void timedOutNativeLookupsKeepTheirPoolSlotsBoundAdditionalWaitsAndRecoverWhenReleased() throws Exception {
        var pool = BoundedStorageDns.lookupExecutor(2);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(2);
        var calls = new AtomicInteger();
        Dns delegate = host -> {
            calls.incrementAndGet();
            started.countDown();
            waitIgnoringInterrupts(release);
            return List.of(InetAddress.getLoopbackAddress());
        };
        var dns = new BoundedStorageDns(300, delegate, pool);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = callers.submit(() -> assertSafeFailure(dns));
            var second = callers.submit(() -> assertSafeFailure(dns));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
            assertEquals(2, pool.getActiveCount());
            assertEquals(0, pool.getQueue().size());
            long waitingSince = System.nanoTime();
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertSafeFailure(dns));
            assertTrue(System.nanoTime() - waitingSince >= TimeUnit.MILLISECONDS.toNanos(200),
                    "A busy pool must allow a handoff within the DNS budget before failing");
            assertEquals(2, calls.get(), "Pool exhaustion must not create another native lookup");
            release.countDown();
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                while (pool.getActiveCount() != 0) Thread.sleep(5);
            });
            assertEquals(List.of(InetAddress.getLoopbackAddress()), dns.lookup("storage.test"));
            assertEquals(3, calls.get());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aColdPoolServesNinetySixConcurrentCachedLookupsWithoutExceedingSixteenThreads() throws Exception {
        var pool = BoundedStorageDns.lookupExecutor(16);
        var release = new CountDownLatch(1);
        var workersStarted = new CountDownLatch(16);
        var callersStarted = new CountDownLatch(96);
        var begin = new CountDownLatch(1);
        var calls = new AtomicInteger();
        List<InetAddress> cached = List.of(InetAddress.getLoopbackAddress());
        var dns = new BoundedStorageDns(2000, host -> {
            calls.incrementAndGet();
            workersStarted.countDown();
            // Hold the first wave briefly to make the cold-pool burst deterministic.
            waitIgnoringInterrupts(release);
            return cached;
        }, pool);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 96).mapToObj(i -> callers.submit(() -> {
                assertTrue(begin.await(2, TimeUnit.SECONDS));
                callersStarted.countDown();
                return dns.lookup("cached.storage.test");
            })).toList();
            try {
                begin.countDown();
                assertTrue(callersStarted.await(2, TimeUnit.SECONDS));
                assertTrue(workersStarted.await(2, TimeUnit.SECONDS));
                release.countDown();
                for (var future : futures) assertEquals(cached, future.get(3, TimeUnit.SECONDS));
                assertEquals(96, calls.get());
                assertTrue(pool.getLargestPoolSize() <= 16);
                assertEquals(0, pool.getQueue().size());
            } finally { release.countDown(); }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void waitingForAWorkerAndResolvingShareOneDeadline() throws Exception {
        var pool = BoundedStorageDns.lookupExecutor(1);
        var initialWorkerStarted = new CountDownLatch(1);
        var releaseInitialWorker = new CountDownLatch(1);
        var lookupStarted = new CountDownLatch(1);
        var releaseLookup = new CountDownLatch(1);
        var callerStarted = new CountDownLatch(1);
        pool.execute(() -> {
            initialWorkerStarted.countDown();
            waitIgnoringInterrupts(releaseInitialWorker);
        });
        var dns = new BoundedStorageDns(1000, host -> {
            lookupStarted.countDown();
            waitIgnoringInterrupts(releaseLookup);
            return List.of(InetAddress.getLoopbackAddress());
        }, pool);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            assertTrue(initialWorkerStarted.await(2, TimeUnit.SECONDS));
            var result = callers.submit(() -> {
                callerStarted.countDown();
                assertSafeFailure(dns);
            });
            assertTrue(callerStarted.await(1, TimeUnit.SECONDS));
            Thread.sleep(600);
            releaseInitialWorker.countDown();
            assertTrue(lookupStarted.await(1, TimeUnit.SECONDS));
            // Only about 400 ms remains: giving the resolver a fresh 1000 ms budget would fail here.
            result.get(700, TimeUnit.MILLISECONDS);
            assertEquals(1, pool.getActiveCount(), "A timed-out native lookup still owns its actual worker");
        } finally {
            releaseInitialWorker.countDown();
            releaseLookup.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void realSdkTransportDnsTimeoutBecomesTheSafeStorageIoException() throws Exception {
        var pool = BoundedStorageDns.lookupExecutor(1);
        var release = new CountDownLatch(1);
        var dns = new BoundedStorageDns(100, host -> {
            waitIgnoringInterrupts(release);
            return List.of(InetAddress.getLoopbackAddress());
        }, pool);
        var http = new OkHttpClient.Builder().dns(dns).callTimeout(2, TimeUnit.SECONDS).build();
        try (var client = MinioClient.builder().endpoint("http://storage-dns.test:9000")
                .credentials("test-backend", "test-only-secret").region("us-east-1").httpClient(http, true).build()) {
            var storage = new MinioObjectStorage(client, 100);
            assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                    StorageConfigurationTests.assertSafe(assertThrows(IOException.class,
                            () -> storage.get("vod-hls", "key")), "GET"));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static void assertSafeFailure(Dns dns) {
        var failure = assertThrows(UnknownHostException.class, () -> dns.lookup("storage.test"));
        assertEquals("Object storage DNS unavailable", failure.getMessage());
        assertNull(failure.getCause());
    }

    private static void waitIgnoringInterrupts(CountDownLatch release) {
        boolean interrupted = false;
        while (release.getCount() != 0) {
            try { release.await(); } catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
