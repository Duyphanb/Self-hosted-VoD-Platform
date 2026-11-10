package com.vodplatform.worker.storage;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.MinioException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Only the operations the worker needs for raw input and HLS output. */
public final class WorkerObjectStorage {
    private static final long PART_SIZE = 5L * 1024 * 1024;
    private final MinioClient client;
    private final StorageProperties properties;
    private final Semaphore putPermits = new Semaphore(StorageConfiguration.MAX_CONCURRENT_PUTS, true);

    WorkerObjectStorage(MinioClient client, StorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** Size used by the job's free-space check before downloading. */
    public long rawSize(String key) throws IOException {
        try {
            return client.statObject(StatObjectArgs.builder().bucket(properties.rawBucket()).object(key).build()).size();
        } catch (MinioException ignored) {
            throw new IOException("Object storage STAT failed");
        } catch (IllegalStateException failure) {
            if (!isTransportFailure(failure)) throw failure;
            throw new IOException("Object storage STAT failed");
        }
    }

    /** Stream to a caller-owned destination without closing it. */
    public long downloadRaw(String key, OutputStream destination) throws IOException {
        Objects.requireNonNull(destination);
        try (var response = client.getObject(GetObjectArgs.builder()
                .bucket(properties.rawBucket()).object(key).build())) {
            return response.transferTo(destination);
        } catch (MinioException | IOException ignored) {
            throw new IOException("Object storage GET failed");
        } catch (IllegalStateException failure) {
            if (!isTransportFailure(failure)) throw failure;
            throw new IOException("Object storage GET failed");
        }
    }

    public void putHls(String key, InputStream source, long size, String contentType) throws IOException {
        if (size < 0) throw new IllegalArgumentException("Object length must be known and non-negative");
        Objects.requireNonNull(source);
        Objects.requireNonNull(contentType);
        boolean acquired = false;
        try {
            // Keep per-host dispatcher slots free for the multipart abort joined by the SDK callback.
            acquired = putPermits.tryAcquire(properties.connectTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!acquired) throw new IOException("Object storage PUT failed");
            client.putObject(PutObjectArgs.builder().bucket(properties.hlsBucket()).object(key)
                    .stream(source, size, PART_SIZE).contentType(contentType).build());
        } catch (MinioException ignored) {
            throw new IOException("Object storage PUT failed");
        } catch (IllegalStateException failure) {
            if (!isTransportFailure(failure)) throw failure;
            throw new IOException("Object storage PUT failed");
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw new IOException("Object storage PUT failed");
        } finally {
            if (acquired) putPermits.release();
        }
    }

    private static boolean isTransportFailure(IllegalStateException failure) {
        Throwable cause = failure.getCause();
        return cause instanceof Exception && !(cause instanceof RuntimeException);
    }
}
