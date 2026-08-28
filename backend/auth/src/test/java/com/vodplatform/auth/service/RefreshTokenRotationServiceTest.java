package com.vodplatform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vodplatform.auth.dto.AuthResponse;
import com.vodplatform.auth.dto.RefreshRequest;
import com.vodplatform.auth.dto.UserProfile;
import com.vodplatform.auth.exception.InvalidRefreshTokenException;
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

class RefreshTokenRotationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final JwtAccessTokenService jwtAccessTokenService = mock(JwtAccessTokenService.class);
    private final UserProfileMapper userProfileMapper = mock(UserProfileMapper.class);
    private final RefreshTokenRotationService service = new RefreshTokenRotationService(
            refreshTokenRepository,
            refreshTokenService,
            jwtAccessTokenService,
            userProfileMapper,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void validTokenIsRevokedAndReplacedInOneRotationResult() {
        UserEntity user = activeUser();
        RefreshTokenEntity token = new RefreshTokenEntity(
                UUID.randomUUID(),
                user,
                "stored-hash",
                NOW.plusSeconds(60),
                NOW.minusSeconds(60)
        );
        UserProfile profile = new UserProfile(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                java.util.List.of()
        );
        when(refreshTokenService.hash("raw-token")).thenReturn("stored-hash");
        when(refreshTokenRepository.findByTokenHash("stored-hash")).thenReturn(Optional.of(token));
        when(jwtAccessTokenService.issue(user)).thenReturn(new IssuedAccessToken("new-access", 900));
        when(refreshTokenService.issue(user)).thenReturn(new IssuedRefreshToken(
                "new-refresh",
                "new-refresh-hash",
                NOW.plusSeconds(604800)
        ));
        when(userProfileMapper.toProfile(user)).thenReturn(profile);

        AuthResponse response = service.rotate(new RefreshRequest("raw-token"));

        assertThat(response.user()).isEqualTo(profile);
        assertThat(response.accessToken()).isEqualTo("new-access");
        assertThat(response.refreshToken()).isEqualTo("new-refresh");
        assertThat(response.expiresInSeconds()).isEqualTo(900);
        assertThat(token.getRevokedAt()).isEqualTo(NOW);
        verify(refreshTokenRepository).findByTokenHash("stored-hash");
    }

    @Test
    void tokenExpiringAtCurrentInstantIsRejectedWithoutIssuingReplacementTokens() {
        UserEntity user = activeUser();
        RefreshTokenEntity token = new RefreshTokenEntity(
                UUID.randomUUID(),
                user,
                "stored-hash",
                NOW,
                NOW.minusSeconds(60)
        );
        when(refreshTokenService.hash("raw-token")).thenReturn("stored-hash");
        when(refreshTokenRepository.findByTokenHash("stored-hash")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.rotate(new RefreshRequest("raw-token")))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessage("Invalid refresh token");

        assertThat(token.getRevokedAt()).isNull();
        verify(refreshTokenService, never()).issue(any());
        verifyNoInteractions(jwtAccessTokenService, userProfileMapper);
    }

    @Test
    void disabledTokenOwnerIsRejectedWithoutIssuingReplacementTokens() {
        UserEntity user = userWithStatus(UserStatus.DISABLED);
        RefreshTokenEntity token = new RefreshTokenEntity(
                UUID.randomUUID(),
                user,
                "stored-hash",
                NOW.plusSeconds(60),
                NOW.minusSeconds(60)
        );
        when(refreshTokenService.hash("raw-token")).thenReturn("stored-hash");
        when(refreshTokenRepository.findByTokenHash("stored-hash")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.rotate(new RefreshRequest("raw-token")))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessage("Invalid refresh token");

        assertThat(token.getRevokedAt()).isNull();
        verify(refreshTokenService, never()).issue(any());
        verifyNoInteractions(jwtAccessTokenService, userProfileMapper);
    }

    private UserEntity activeUser() {
        return userWithStatus(UserStatus.ACTIVE);
    }

    private UserEntity userWithStatus(UserStatus status) {
        return new UserEntity(
                UUID.randomUUID(),
                "viewer@example.com",
                "password-hash",
                "Viewer",
                status,
                NOW.minusSeconds(60),
                NOW.minusSeconds(60)
        );
    }
}
