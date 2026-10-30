package com.vodplatform.common.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class StorageHealthIndicatorTests {
    @Test
    void missingProbeKeyMeansAuthenticatedStorageIsUp() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.getObject(any())).thenThrow(error("NoSuchKey"));
        try (var indicator = new StorageHealthIndicator(client, "vod-hls")) {
            var health = indicator.health();
            assertEquals(Status.UP, health.getStatus());
            assertTrue(health.getDetails().isEmpty());
        }
    }

    @Test
    void deniedInvalidSignatureTransportAndTimeoutAreDownWithOnlyFixedDetails() throws Exception {
        for (Exception failure : new Exception[] {error("AccessDenied"), error("SignatureDoesNotMatch"),
                new IllegalStateException(new IOException("private diagnostic")),
                new IllegalStateException(new java.net.SocketTimeoutException("private diagnostic"))}) {
            MinioClient client = mock(MinioClient.class);
            when(client.getObject(any())).thenThrow(failure);
            try (var indicator = new StorageHealthIndicator(client, "vod-hls")) {
                var health = indicator.health();
                assertEquals(Status.DOWN, health.getStatus());
                assertEquals(java.util.Map.of("storage", "Authenticated storage probe failed"), health.getDetails());
            }
        }
    }

    @Test
    void healthTransportHasAnIndependentDispatcherAndAtMostTwoSecondCallBudget() throws Exception {
        var config = new StorageConfiguration();
        var properties = StorageConfigurationTests.properties("http://localhost:9000", 10000, 60000, 60000);
        try (var client = config.minioClient(properties);
                var probeClient = StorageConfiguration.client(properties, StorageConfiguration.HEALTH_TIMEOUT_MS)) {
            var http = StorageConfigurationTests.transport(probeClient);
            assertEquals(2000, http.callTimeoutMillis());
            assertEquals(2000, http.connectTimeoutMillis());
            assertEquals(2000, http.readTimeoutMillis());
            assertNotSame(StorageConfigurationTests.transport(client).dispatcher(), http.dispatcher());
        }
    }

    @Test
    void oneHundredConcurrentReadinessCallersShareOneHealthyProbe() throws Exception {
        MinioClient client = mock(MinioClient.class);
        var ready = new CountDownLatch(100);
        var go = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var clock = new AtomicLong();
        when(client.getObject(any())).thenAnswer(invocation -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            throw error("NoSuchKey");
        });
        try (var indicator = new StorageHealthIndicator(client, "vod-hls", 2000, clock::get);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<Future<Health>>();
            try {
                for (int i = 0; i < 100; i++) {
                    results.add(executor.submit(() -> {
                        ready.countDown();
                        assertTrue(go.await(5, TimeUnit.SECONDS));
                        return indicator.health();
                    }));
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                go.countDown();
                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertTrue(results.stream().noneMatch(Future::isDone), "A healthy busy probe must not return DOWN");
                release.countDown();
                for (var result : results) {
                    assertEquals(Status.UP, result.get(2, TimeUnit.SECONDS).getStatus());
                }
                verify(client, times(1)).getObject(any());
            } finally {
                go.countDown();
                release.countDown();
            }
        }
    }

    @Test
    void requestFloodUsesOneProbePerTtlMeasuredFromResultCompletion() throws Exception {
        MinioClient client = mock(MinioClient.class);
        var clock = new AtomicLong();
        when(client.getObject(any())).thenAnswer(invocation -> {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(750));
            throw error("NoSuchKey");
        });
        try (var indicator = new StorageHealthIndicator(client, "vod-hls", 2000, clock::get)) {
            assertEquals(Status.UP, indicator.health().getStatus());
            clock.addAndGet(StorageHealthIndicator.CACHE_TTL_NANOS - 1);
            for (int i = 0; i < 100; i++) assertEquals(Status.UP, indicator.health().getStatus());
            verify(client, times(1)).getObject(any());
            clock.incrementAndGet();
            assertEquals(Status.UP, indicator.health().getStatus());
            verify(client, times(2)).getObject(any());
        }
    }

    @Test
    void failedProbeIsCachedSafelyAndCanRecoverAfterExpiry() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.getObject(any())).thenThrow(error("AccessDenied"), error("NoSuchKey"));
        var clock = new AtomicLong();
        try (var indicator = new StorageHealthIndicator(client, "vod-hls", 2000, clock::get)) {
            var failed = indicator.health();
            assertEquals(Status.DOWN, failed.getStatus());
            assertEquals(java.util.Map.of("storage", "Authenticated storage probe failed"), failed.getDetails());
            for (int i = 0; i < 100; i++) assertSame(failed, indicator.health());
            verify(client, times(1)).getObject(any());
            clock.addAndGet(StorageHealthIndicator.CACHE_TTL_NANOS);
            var recovered = indicator.health();
            assertEquals(Status.UP, recovered.getStatus());
            assertTrue(recovered.getDetails().isEmpty());
            verify(client, times(2)).getObject(any());
        }
    }

    @Test
    void laterCallerKeepsOriginalDeadlineAndExpiredCacheCannotQueueBehindStalledNativeWork() throws Exception {
        MinioClient client = mock(MinioClient.class);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var clock = new AtomicLong();
        when(client.getObject(any())).thenAnswer(invocation -> {
            started.countDown();
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) { /* Native resolver ignores it. */ }
            }
            throw error("NoSuchKey");
        });
        try (var indicator = new StorageHealthIndicator(client, "vod-hls", 2000, clock::get);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var first = executor.submit(() -> { return indicator.health(); });
                assertTrue(started.await(2, TimeUnit.SECONDS));
                first.cancel(true); // An interrupted caller must not change the shared probe's result.
                clock.set(TimeUnit.MILLISECONDS.toNanos(1900));
                long before = System.nanoTime();
                assertTimeoutPreemptively(Duration.ofMillis(500),
                        () -> assertEquals(Status.DOWN, indicator.health().getStatus()));
                assertTrue(System.nanoTime() - before >= TimeUnit.MILLISECONDS.toNanos(50),
                        "A busy probe must wait for its original deadline instead of failing immediately");
                clock.addAndGet(2 * StorageHealthIndicator.CACHE_TTL_NANOS);
                assertTimeoutPreemptively(Duration.ofMillis(300), () -> {
                    for (int i = 0; i < 100; i++) assertEquals(Status.DOWN, indicator.health().getStatus());
                });
                verify(client, times(1)).getObject(any());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void outerDeadlineBoundsRealSdkDnsAndBusyProbesCannotAccumulateTasks() throws Exception {
        var resolving = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var lookupCount = new AtomicInteger();
        int port;
        try (var closedPort = new ServerSocket(0)) { port = closedPort.getLocalPort(); }
        var http = new OkHttpClient.Builder().connectTimeout(100, TimeUnit.MILLISECONDS)
                .readTimeout(100, TimeUnit.MILLISECONDS).writeTimeout(100, TimeUnit.MILLISECONDS)
                .callTimeout(100, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false)
                .dns(host -> {
                    lookupCount.incrementAndGet();
                    resolving.countDown();
                    boolean interrupted = false;
                    // Model native DNS which does not honor interruption or an OkHttp call timeout.
                    while (release.getCount() != 0) {
                        try { release.await(); } catch (InterruptedException ignored) { interrupted = true; }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                    return List.of(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
                }).build();
        var client = MinioClient.builder().endpoint("http://storage-readiness.test:" + port)
                .credentials("test-backend", "test-only-secret").region("us-east-1").httpClient(http, true).build();
        client.setRetry(java.util.Set.of(), 0L, 1);
        try (var indicator = new StorageHealthIndicator(client, "vod-hls", 100)) {
            assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> assertEquals(Status.DOWN, indicator.health().getStatus()));
            assertTrue(resolving.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(300), () -> {
                for (int i = 0; i < 20; i++) assertEquals(Status.DOWN, indicator.health().getStatus());
            });
            assertEquals(1, lookupCount.get(), "A timed-out DNS operation must retain the sole probe slot");
        } finally {
            release.countDown();
        }
    }

    private static ErrorResponseException error(String code) {
        return new ErrorResponseException(new ErrorResponse(code, "private message", "private bucket", "private key",
                "private resource", "private request", "private host"), null, "private HTTP trace");
    }
}
