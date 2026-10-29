package com.vodplatform.common.storage;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

public final class StorageHealthIndicator implements HealthIndicator, AutoCloseable {
    // A non-UUID segment and attempt-0 cannot be a real asset/encoding attempt.
    static final String PROBE_KEY = "video-assets/readiness-probe/attempt-0/never-written";
    private final MinioClient client;
    private final String bucket;
    private final Semaphore probePermit = new Semaphore(1);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon(true).name("storage-readiness-", 0).factory());
    private final long probeTimeoutMs;

    StorageHealthIndicator(MinioClient client, String bucket) {
        this(client, bucket, StorageConfiguration.HEALTH_TIMEOUT_MS);
    }

    StorageHealthIndicator(MinioClient client, String bucket, long probeTimeoutMs) {
        this.client = client;
        this.bucket = bucket;
        this.probeTimeoutMs = probeTimeoutMs;
    }

    @Override
    public Health health() {
        // Socket/call timeouts cannot interrupt JVM DNS. Bound the caller separately, while retaining
        // the permit until the actual operation ends so a stalled resolver cannot accumulate tasks.
        if (!probePermit.tryAcquire()) return unavailable();
        java.util.concurrent.Future<Health> pending;
        try {
            pending = executor.submit(() -> { return probe(); });
        } catch (RejectedExecutionException ignored) {
            probePermit.release();
            return unavailable();
        }
        try {
            return pending.get(probeTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return unavailable();
        } catch (ExecutionException | TimeoutException ignored) {
            // The single task may still be resolving DNS. Its finally block owns the permit.
            return unavailable();
        }
    }

    private Health probe() {
        try {
            try (var ignored = client.getObject(GetObjectArgs.builder().bucket(bucket).object(PROBE_KEY).build())) {
                return Health.up().build();
            } catch (ErrorResponseException exception) {
                if ("NoSuchKey".equals(exception.errorResponse().code())) {
                    return Health.up().build();
                }
                return unavailable();
            } catch (Exception ignored) {
                return unavailable();
            }
        } finally {
            probePermit.release();
        }
    }

    private Health unavailable() {
        return Health.down().withDetail("storage", "Authenticated storage probe failed").build();
    }

    @Override
    public void close() throws Exception {
        executor.shutdownNow();
        client.close();
    }
}
