package com.vodplatform.auth.service;

import com.vodplatform.auth.config.AuthTokenProperties;
import com.vodplatform.auth.persistence.RefreshTokenEntity;
import com.vodplatform.auth.persistence.RefreshTokenRepository;
import com.vodplatform.auth.persistence.UserEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthTokenProperties properties;
    private final SecureRandom secureRandom;
    private final Clock clock;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            AuthTokenProperties properties,
            SecureRandom secureRandom,
            Clock clock
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.properties = properties;
        this.secureRandom = secureRandom;
        this.clock = clock;
    }

    public IssuedRefreshToken issue(UserEntity user) {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String hash = hash(value);
        Instant createdAt = clock.instant();
        Instant expiresAt = createdAt.plus(properties.refreshTokenTtl());

        refreshTokenRepository.save(new RefreshTokenEntity(
                UUID.randomUUID(),
                user,
                hash,
                expiresAt,
                createdAt
        ));
        return new IssuedRefreshToken(value, hash, expiresAt);
    }

    public String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
