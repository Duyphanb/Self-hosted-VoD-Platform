package com.vodplatform.auth.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.tokens")
public record AuthTokenProperties(
        String secret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl
) {

    private static final int MINIMUM_HS256_SECRET_BYTES = 32;

    public AuthTokenProperties {
        if (secret == null
                || secret.isBlank()
                || secret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_HS256_SECRET_BYTES) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 UTF-8 bytes");
        }
        if (!between(accessTokenTtl, Duration.ofMinutes(1), Duration.ofMinutes(15))) {
            throw new IllegalArgumentException("Access token TTL must be between 1 and 15 minutes");
        }
        if (!between(refreshTokenTtl, Duration.ofDays(1), Duration.ofDays(30))) {
            throw new IllegalArgumentException("Session TTL must be between 1 and 30 days");
        }
    }

    private static boolean between(Duration ttl, Duration min, Duration max) {
        return ttl != null && ttl.compareTo(min) >= 0 && ttl.compareTo(max) <= 0;
    }
}
