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
    void timedOutNativeLookupsKeepTheirPoolSlotsRejectExtraWorkAndRecoverWhenReleased() throws Exception {
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
            assertTimeoutPreemptively(Duration.ofMillis(200), () -> assertSafeFailure(dns));
            assertEquals(2, calls.get(), "Pool exhaustion must reject rather than create another lookup");
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
