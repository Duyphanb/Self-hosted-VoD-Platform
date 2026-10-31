package com.vodplatform.common.storage;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

public final class StorageHealthIndicator implements HealthIndicator, AutoCloseable {
    // A non-UUID segment and attempt-0 cannot be a real asset/encoding attempt.
    static final String PROBE_KEY = "video-assets/readiness-probe/attempt-0/never-written";
    static final long CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private final MinioClient client;
    private final String bucket;
    private final Object stateLock = new Object();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon(true).name("storage-readiness-", 0).factory());
    private final long probeTimeoutMs;
    private final LongSupplier nanoTime;
    // Guarded by stateLock. The actual operation owns this slot until it really ends,
    // including when its logical result has already timed out.
    private Probe inFlight;
    private Health cachedResult;
    private long cacheCompletedNanos;
    private boolean closed;

    private static final class Probe {
        private final long deadlineNanos;
        private final CompletableFuture<Health> result = new CompletableFuture<>();

        private Probe(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }
    }

    StorageHealthIndicator(MinioClient client, String bucket) {
        this(client, bucket, StorageConfiguration.HEALTH_TIMEOUT_MS);
    }

    StorageHealthIndicator(MinioClient client, String bucket, long probeTimeoutMs) {
        this(client, bucket, probeTimeoutMs, System::nanoTime);
    }

    StorageHealthIndicator(MinioClient client, String bucket, long probeTimeoutMs, LongSupplier nanoTime) {
        this.client = client;
        this.bucket = bucket;
        this.probeTimeoutMs = probeTimeoutMs;
        this.nanoTime = nanoTime;
    }

    @Override
    public Health health() {
        Probe pending;
        synchronized (stateLock) {
            if (closed) return unavailable();
            long now = nanoTime.getAsLong();
            if (cachedResult != null && now - cacheCompletedNanos < CACHE_TTL_NANOS) {
                return cachedResult;
            }
            if (inFlight == null) {
                var started = new Probe(now + TimeUnit.MILLISECONDS.toNanos(probeTimeoutMs));
                inFlight = started;
                try {
                    executor.execute(() -> runProbe(started));
                } catch (RejectedExecutionException ignored) {
                    inFlight = null;
                    publish(started, unavailable(), now);
                    return cachedResult;
                }
            }
            pending = inFlight;
        }
        // All callers share the original deadline, rather than restarting a two-second
        // wait when they join. A timed-out native operation cannot accumulate new tasks.
        long remainingNanos = pending.deadlineNanos - nanoTime.getAsLong();
        if (remainingNanos <= 0) return timedOut(pending);
        try {
            return pending.result.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return unavailable();
        } catch (TimeoutException ignored) {
            return timedOut(pending);
        } catch (ExecutionException ignored) {
            return unavailable();
        }
    }

    private void runProbe(Probe pending) {
        Health observed = unavailable();
        try {
            observed = probe();
        } finally {
            synchronized (stateLock) {
                long now = nanoTime.getAsLong();
                publish(pending, now - pending.deadlineNanos >= 0 ? unavailable() : observed, now);
                if (inFlight == pending) inFlight = null;
            }
        }
    }

    private Health timedOut(Probe pending) {
        synchronized (stateLock) {
            publish(pending, unavailable(), nanoTime.getAsLong());
            return pending.result.getNow(unavailable());
        }
    }

    // Called with stateLock held. The first completion owns the cache timestamp;
    // late native success cannot replace a deadline failure or extend its TTL.
    private void publish(Probe pending, Health result, long completedNanos) {
        if (pending.result.complete(result)) {
            cachedResult = result;
            cacheCompletedNanos = completedNanos;
        }
    }

    private Health probe() {
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
    }

    private Health unavailable() {
        return Health.down().withDetail("storage", "Authenticated storage probe failed").build();
    }

    @Override
    public void close() throws Exception {
        synchronized (stateLock) {
            closed = true;
            if (inFlight != null) publish(inFlight, unavailable(), nanoTime.getAsLong());
        }
        executor.shutdownNow();
        client.close();
    }
}
