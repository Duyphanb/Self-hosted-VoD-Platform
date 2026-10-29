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

public final class MinioObjectStorage implements ObjectStorage {
    // Fixed part size bounds SDK buffering independently of the upload's total length.
    private static final long PART_SIZE = 5L * 1024 * 1024;
    private final MinioClient client;

    public MinioObjectStorage(MinioClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public void put(String bucket, String key, InputStream source, long size, String contentType) throws IOException {
        if (size < 0) {
            throw new IllegalArgumentException("Object length must be known and non-negative");
        }
        Objects.requireNonNull(source);
        try {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(source, size, PART_SIZE).contentType(contentType).build());
        } catch (MinioException e) {
            // SDK exceptions may contain internal URLs, keys and signed headers. Do not propagate them.
            throw new IOException("Object storage PUT failed");
        }
    }

    @Override
    public InputStream get(String bucket, String key) throws IOException {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (MinioException e) {
            throw new IOException("Object storage GET failed");
        }
    }

    @Override
    public void delete(String bucket, String key) throws IOException {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (MinioException e) {
            throw new IOException("Object storage DELETE failed");
        }
    }
}
