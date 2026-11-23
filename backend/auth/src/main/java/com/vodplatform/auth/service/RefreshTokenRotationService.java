package com.vodplatform.auth.service;

import com.vodplatform.auth.exception.InvalidRefreshTokenException;
import com.vodplatform.auth.persistence.AuthSessionEntity;
import com.vodplatform.auth.persistence.RefreshTokenEntity;
import com.vodplatform.auth.persistence.UserEntity;
import com.vodplatform.auth.persistence.UserRepository;
import com.vodplatform.auth.persistence.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenRotationService {

    private final UserRepository users;
    private final AuthSessionService sessions;
    private final RefreshTokenService refreshTokenService;
    private final JwtAccessTokenService jwtAccessTokenService;
    private final UserProfileMapper userProfileMapper;
    private final Clock clock;

    public RefreshTokenRotationService(
            UserRepository users,
            AuthSessionService sessions,
            RefreshTokenService refreshTokenService,
            JwtAccessTokenService jwtAccessTokenService,
            UserProfileMapper userProfileMapper,
            Clock clock
    ) {
        this.users = users;
        this.sessions = sessions;
        this.refreshTokenService = refreshTokenService;
        this.jwtAccessTokenService = jwtAccessTokenService;
        this.userProfileMapper = userProfileMapper;
        this.clock = clock;
    }

    // A replay returns 401 after committing the family revocation.
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public IssuedAuthentication rotate(String token) {
        var location = refreshTokenService.locate(token).orElseThrow(InvalidRefreshTokenException::new);
        users.lockAuthUsers(List.of(location.userId()));
        AuthSessionEntity session = sessions.lock(location.sessionId(), location.userId())
                .orElseThrow(InvalidRefreshTokenException::new);
        RefreshTokenEntity currentToken = refreshTokenService.lock(location)
                .orElseThrow(InvalidRefreshTokenException::new);
        UserEntity user = currentToken.getUser();
        UserStatus status = user.getStatus();
        // A competing rotation/logout can hold the lock until after expiration.
        Instant now = clock.instant();

        if (!session.isActiveAt(now) || !currentToken.getExpiresAt().isAfter(now) || status != UserStatus.ACTIVE) {
            throw new InvalidRefreshTokenException();
        }
        if (currentToken.getRevokedAt() != null) {
            sessions.revoke(session, now);
            throw new InvalidRefreshTokenException();
        }

        currentToken.revoke(now);
        IssuedAccessToken accessToken = jwtAccessTokenService.issue(user, session);
        IssuedRefreshToken refreshToken = refreshTokenService.issue(user, session);
        return new IssuedAuthentication(
                userProfileMapper.toProfile(user),
                accessToken.value(),
                refreshToken.value(),
                accessToken.expiresInSeconds(), refreshToken.expiresAt()
        );
    }
}
