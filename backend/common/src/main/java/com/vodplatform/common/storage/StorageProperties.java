package com.vodplatform.common.storage;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "storage")
public record StorageProperties(String endpoint, String accessKey, String secretKey,
        long connectTimeoutMs, long readTimeoutMs, long writeTimeoutMs, String hlsBucket) {
    public StorageProperties {
        URI uri;
        try {
            uri = endpoint == null ? null : new URI(endpoint);
        } catch (URISyntaxException ignored) {
            throw new IllegalArgumentException("Storage endpoint must be a valid HTTP(S) origin");
        }
        if (uri == null || uri.getHost() == null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))
                || uri.getPort() == 0 || uri.getPort() > 65_535) {
            throw new IllegalArgumentException("Storage endpoint must be a valid HTTP(S) origin");
        }
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("Storage credentials must not be blank");
        }
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0 || writeTimeoutMs <= 0
                || connectTimeoutMs > Integer.MAX_VALUE || readTimeoutMs > Integer.MAX_VALUE
                || writeTimeoutMs > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Storage timeouts must be positive milliseconds within integer range");
        }
        if (hlsBucket == null || hlsBucket.isBlank()) {
            throw new IllegalArgumentException("Storage HLS bucket must not be blank");
        }
    }

    @Override
    public String toString() {
        return "StorageProperties[redacted]";
    }
}
