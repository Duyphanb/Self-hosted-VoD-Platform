package com.vodplatform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vodplatform.auth.dto.RefreshRequest;
import com.vodplatform.auth.persistence.RefreshTokenEntity;
import com.vodplatform.auth.persistence.RefreshTokenRepository;
import com.vodplatform.auth.persistence.UserEntity;
import com.vodplatform.auth.persistence.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LogoutServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final LogoutService service = new LogoutService(
            refreshTokenRepository,
            refreshTokenService,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void knownTokenIsRevokedAtCurrentInstant() {
        RefreshTokenEntity token = storedToken();
        when(refreshTokenService.hash("raw-token")).thenReturn("stored-hash");
        when(refreshTokenRepository.findByTokenHash("stored-hash")).thenReturn(Optional.of(token));

        service.logout(new RefreshRequest("raw-token"));

        assertThat(token.getRevokedAt()).isEqualTo(NOW);
        verify(refreshTokenRepository).findByTokenHash("stored-hash");
    }

    @Test
    void omittedTokenIsAnIdempotentNoOp() {
        service.logout(null);

        verifyNoInteractions(refreshTokenService, refreshTokenRepository);
    }

    @Test
    void unknownTokenIsAnIdempotentNoOp() {
        when(refreshTokenService.hash("unknown-token")).thenReturn("unknown-hash");
        when(refreshTokenRepository.findByTokenHash("unknown-hash")).thenReturn(Optional.empty());

        service.logout(new RefreshRequest("unknown-token"));

        verify(refreshTokenRepository).findByTokenHash("unknown-hash");
    }

    @Test
    void repeatedLogoutPreservesOriginalRevocationTimestamp() {
        Instant originalRevocation = NOW.minusSeconds(30);
        RefreshTokenEntity token = storedToken();
        token.revoke(originalRevocation);
        when(refreshTokenService.hash("raw-token")).thenReturn("stored-hash");
        when(refreshTokenRepository.findByTokenHash("stored-hash")).thenReturn(Optional.of(token));

        service.logout(new RefreshRequest("raw-token"));

        assertThat(token.getRevokedAt()).isEqualTo(originalRevocation);
    }

    private RefreshTokenEntity storedToken() {
        Instant createdAt = NOW.minusSeconds(60);
        UserEntity user = new UserEntity(
                UUID.randomUUID(),
                "viewer@example.com",
                "password-hash",
                "Viewer",
                UserStatus.ACTIVE,
                createdAt,
                createdAt
        );
        return new RefreshTokenEntity(
                UUID.randomUUID(),
                user,
                "stored-hash",
                NOW.plusSeconds(60),
                createdAt
        );
    }
}
