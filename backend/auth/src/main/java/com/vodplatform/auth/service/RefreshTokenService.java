package com.vodplatform.auth.service;

import com.vodplatform.auth.persistence.AuthSessionEntity;
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
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final SecureRandom secureRandom;
    private final Clock clock;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            SecureRandom secureRandom,
            Clock clock
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.secureRandom = secureRandom;
        this.clock = clock;
    }

    public IssuedRefreshToken issue(UserEntity user, AuthSessionEntity session) {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String hash = hash(value);
        Instant createdAt = clock.instant();

        refreshTokenRepository.save(new RefreshTokenEntity(
                UUID.randomUUID(),
                user,
                hash,
                session,
                createdAt
        ));
        return new IssuedRefreshToken(value, hash, session.getExpiresAt());
    }

    Optional<LocatedToken> locate(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            return Optional.empty();
        }
        String tokenHash = hash(value);
        return refreshTokenRepository.locate(tokenHash)
                .map(location -> new LocatedToken(tokenHash, location.getUserId(), location.getSessionId()));
    }

    Optional<RefreshTokenEntity> lock(LocatedToken location) {
        return refreshTokenRepository.findByTokenHash(location.hash())
                .filter(token -> location.sessionId().equals(token.getSessionId()));
    }

    public String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    record LocatedToken(String hash, UUID userId, UUID sessionId) {
    }
}
