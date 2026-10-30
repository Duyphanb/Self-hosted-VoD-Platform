package com.vodplatform.common.storage;

import com.vodplatform.shared.storage.ObjectStorage;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.MinioException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class MinioObjectStorage implements ObjectStorage {
    // Fixed part size bounds SDK buffering independently of the upload's total length.
    private static final long PART_SIZE = 5L * 1024 * 1024;
    private final MinioClient client;
    private final Semaphore putPermits = new Semaphore(StorageConfiguration.MAX_CONCURRENT_PUTS, true);
    private final long admissionTimeoutMs;

    public MinioObjectStorage(MinioClient client, long admissionTimeoutMs) {
        this.client = Objects.requireNonNull(client);
        this.admissionTimeoutMs = admissionTimeoutMs;
    }

    @Override
    public void put(String bucket, String key, InputStream source, long size, String contentType) throws IOException {
        if (size < 0) {
            throw new IllegalArgumentException("Object length must be known and non-negative");
        }
        Objects.requireNonNull(source);
        Objects.requireNonNull(contentType, "Object content type must not be null");
        boolean acquired = false;
        try {
            // SDK 9.0.3 joins abort inside an OkHttp callback, retaining that PUT's slot.
            // Eight PUTs stay below 32 per-host slots, reserving abort/GET/DELETE headroom
            // and bounding multipart buffers to roughly 64-96 MiB of the effective JVM heap.
            acquired = putPermits.tryAcquire(admissionTimeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) throw new IOException("Object storage PUT failed");
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(source, size, PART_SIZE).contentType(contentType).build());
        } catch (MinioException e) {
            // The SDK redacts signing credentials, but diagnostics retain internal URL, bucket and key.
            throw new IOException("Object storage PUT failed");
        } catch (IllegalStateException e) {
            if (!isTransportFailure(e)) throw e;
            throw new IOException("Object storage PUT failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Object storage PUT failed");
        } finally {
            if (acquired) putPermits.release();
        }
    }

    @Override
    public InputStream get(String bucket, String key) throws IOException {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (MinioException e) {
            throw new IOException("Object storage GET failed");
        } catch (IllegalStateException e) {
            if (!isTransportFailure(e)) throw e;
            throw new IOException("Object storage GET failed");
        }
    }

    @Override
    public void delete(String bucket, String key) throws IOException {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (MinioException e) {
            throw new IOException("Object storage DELETE failed");
        } catch (IllegalStateException e) {
            if (!isTransportFailure(e)) throw e;
            throw new IOException("Object storage DELETE failed");
        }
    }

    private static boolean isTransportFailure(IllegalStateException failure) {
        // The SDK can unwrap TLS trust failures to a checked security exception instead of IOException.
        Throwable cause = failure.getCause();
        return cause instanceof Exception && !(cause instanceof RuntimeException);
    }
}
