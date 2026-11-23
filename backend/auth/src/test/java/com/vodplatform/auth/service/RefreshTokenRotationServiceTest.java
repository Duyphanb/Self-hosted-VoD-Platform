package com.vodplatform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vodplatform.auth.service.IssuedAuthentication;
import com.vodplatform.auth.dto.UserProfile;
import com.vodplatform.auth.exception.InvalidRefreshTokenException;
import com.vodplatform.auth.persistence.AuthSessionEntity;
import com.vodplatform.auth.persistence.RefreshTokenEntity;
import com.vodplatform.auth.persistence.UserEntity;
import com.vodplatform.auth.persistence.UserRepository;
import com.vodplatform.auth.persistence.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RefreshTokenRotationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

    private final UserRepository users = mock(UserRepository.class);
    private final AuthSessionService sessions = mock(AuthSessionService.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final JwtAccessTokenService jwtAccessTokenService = mock(JwtAccessTokenService.class);
    private final UserProfileMapper userProfileMapper = mock(UserProfileMapper.class);
    private final RefreshTokenRotationService service = new RefreshTokenRotationService(
            users,
            sessions,
            refreshTokenService,
            jwtAccessTokenService,
            userProfileMapper,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void rejectsTokenThatExpiresWhileWaitingForItsDatabaseLock() {
        Clock clock = mock(Clock.class);
        AtomicBoolean lockAcquired = new AtomicBoolean();
        when(clock.instant()).thenAnswer(ignored -> lockAcquired.get() ? NOW.plusSeconds(2) : NOW);
        RefreshTokenEntity token = new RefreshTokenEntity(UUID.randomUUID(), activeUser(),
                "stored-hash", NOW.plusSeconds(1), NOW.minusSeconds(60));
        var session = sessionFor(token.getUser());
        var location = locate(session);
        when(refreshTokenService.lock(location)).thenAnswer(ignored -> {
            lockAcquired.set(true);
            return Optional.of(token);
        });
        RefreshTokenRotationService delayedService = new RefreshTokenRotationService(
                users, sessions, refreshTokenService, jwtAccessTokenService, userProfileMapper, clock);
        assertThatThrownBy(() -> delayedService.rotate("raw-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(token.getRevokedAt()).isNull();
        verify(refreshTokenService, never()).issue(any(), any());
        verifyNoInteractions(jwtAccessTokenService);
    }

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
        var session = sessionFor(user);
        var location = locate(session);
        when(refreshTokenService.lock(location)).thenReturn(Optional.of(token));
        when(jwtAccessTokenService.issue(user, session)).thenReturn(new IssuedAccessToken("new-access", 900));
        when(refreshTokenService.issue(user, session)).thenReturn(new IssuedRefreshToken(
                "new-refresh",
                "new-refresh-hash",
                NOW.plusSeconds(604800)
        ));
        when(userProfileMapper.toProfile(user)).thenReturn(profile);

        IssuedAuthentication response = service.rotate("raw-token");

        assertThat(response.user()).isEqualTo(profile);
        assertThat(response.accessToken()).isEqualTo("new-access");
        assertThat(response.refreshToken()).isEqualTo("new-refresh");
        assertThat(response.expiresInSeconds()).isEqualTo(900);
        assertThat(token.getRevokedAt()).isEqualTo(NOW);
        verify(refreshTokenService).lock(location);
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
        when(refreshTokenService.lock(locate(sessionFor(user)))).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.rotate("raw-token"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessage("Invalid refresh token");

        assertThat(token.getRevokedAt()).isNull();
        verify(refreshTokenService, never()).issue(any(), any());
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
        when(refreshTokenService.lock(locate(sessionFor(user)))).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.rotate("raw-token"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessage("Invalid refresh token");

        assertThat(token.getRevokedAt()).isNull();
        verify(refreshTokenService, never()).issue(any(), any());
        verifyNoInteractions(jwtAccessTokenService, userProfileMapper);
    }

    private UserEntity activeUser() {
        return userWithStatus(UserStatus.ACTIVE);
    }

    private AuthSessionEntity sessionFor(UserEntity user) {
        return new AuthSessionEntity(UUID.randomUUID(), user.getId(), NOW.minusSeconds(60), NOW.plusSeconds(604800));
    }

    private RefreshTokenService.LocatedToken locate(AuthSessionEntity session) {
        var location = new RefreshTokenService.LocatedToken("stored-hash", session.getUserId(), session.getId());
        when(refreshTokenService.locate("raw-token")).thenReturn(Optional.of(location));
        when(sessions.lock(session.getId(), session.getUserId())).thenReturn(Optional.of(session));
        return location;
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
