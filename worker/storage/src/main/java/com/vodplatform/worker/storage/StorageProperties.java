package com.vodplatform.worker.storage;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.core.env.Environment;
import org.springframework.util.PlaceholderResolutionException;

/** Settings supplied by Compose using the worker's scoped storage identity. */
record StorageProperties(String endpoint, String accessKey, String secretKey, String rawBucket, String hlsBucket,
        long connectTimeoutMs, long readTimeoutMs, long writeTimeoutMs) {
    StorageProperties {
        URI uri;
        try {
            uri = endpoint == null ? null : new URI(endpoint);
        } catch (URISyntaxException ignored) {
            throw invalidEndpoint();
        }
        if (uri == null || uri.getHost() == null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))
                || uri.getPort() == 0 || uri.getPort() > 65_535) {
            throw invalidEndpoint();
        }
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("MINIO_ACCESS_KEY and MINIO_SECRET_KEY must not be blank");
        }
        if (rawBucket == null || rawBucket.isBlank() || hlsBucket == null || hlsBucket.isBlank()
                || rawBucket.equals(hlsBucket)) {
            throw new IllegalArgumentException("MINIO_BUCKET_RAW and MINIO_BUCKET_HLS must be non-blank and distinct");
        }
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0 || writeTimeoutMs <= 0
                || connectTimeoutMs > Integer.MAX_VALUE || readTimeoutMs > Integer.MAX_VALUE
                || writeTimeoutMs > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("MinIO stall timeouts must be positive milliseconds within integer range");
        }
    }

    static StorageProperties fromEnvironment(Environment env) {
        return new StorageProperties(setting(env, "MINIO_ENDPOINT"), setting(env, "MINIO_ACCESS_KEY"),
                setting(env, "MINIO_SECRET_KEY"), setting(env, "MINIO_BUCKET_RAW"),
                setting(env, "MINIO_BUCKET_HLS"), timeout(env, "MINIO_CONNECT_TIMEOUT_MS"),
                timeout(env, "MINIO_READ_TIMEOUT_MS"), timeout(env, "MINIO_WRITE_TIMEOUT_MS"));
    }

    private static String setting(Environment env, String name) {
        try {
            return env.getProperty(name);
        } catch (PlaceholderResolutionException ignored) {
            throw new IllegalArgumentException(name + " could not be resolved");
        }
    }

    private static int timeout(Environment env, String name) {
        try {
            int timeout = Integer.parseInt(setting(env, name));
            if (timeout > 0) return timeout;
        } catch (NumberFormatException ignored) {
            // Report the variable name without its configured value.
        }
        throw new IllegalArgumentException(name + " must be a positive integer");
    }

    private static IllegalArgumentException invalidEndpoint() {
        return new IllegalArgumentException("MINIO_ENDPOINT must be a valid HTTP(S) origin");
    }

    @Override
    public String toString() {
        return "StorageProperties[redacted]";
    }
}
