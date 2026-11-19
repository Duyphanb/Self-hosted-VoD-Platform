package com.vodplatform.auth.service;

import com.vodplatform.auth.exception.InvalidRefreshTokenException;
import com.vodplatform.auth.persistence.RefreshTokenEntity;
import com.vodplatform.auth.persistence.RefreshTokenRepository;
import com.vodplatform.auth.persistence.UserEntity;
import com.vodplatform.auth.persistence.UserStatus;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenRotationService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtAccessTokenService jwtAccessTokenService;
    private final UserProfileMapper userProfileMapper;
    private final Clock clock;

    public RefreshTokenRotationService(
            RefreshTokenRepository refreshTokenRepository,
            RefreshTokenService refreshTokenService,
            JwtAccessTokenService jwtAccessTokenService,
            UserProfileMapper userProfileMapper,
            Clock clock
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenService = refreshTokenService;
        this.jwtAccessTokenService = jwtAccessTokenService;
        this.userProfileMapper = userProfileMapper;
        this.clock = clock;
    }

    @Transactional
    public IssuedAuthentication rotate(String token) {
        if (token == null || token.isBlank() || token.length() > 128) throw new InvalidRefreshTokenException();
        String tokenHash = refreshTokenService.hash(token);
        RefreshTokenEntity currentToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(InvalidRefreshTokenException::new);
        // A competing rotation/logout can hold the row lock until after expiration.
        Instant now = clock.instant();
        UserEntity user = currentToken.getUser();

        if (currentToken.getRevokedAt() != null
                || !currentToken.getExpiresAt().isAfter(now)
                || user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidRefreshTokenException();
        }

        currentToken.revoke(now);
        IssuedAccessToken accessToken = jwtAccessTokenService.issue(user);
        IssuedRefreshToken refreshToken = refreshTokenService.issue(user);
        return new IssuedAuthentication(
                userProfileMapper.toProfile(user),
                accessToken.value(),
                refreshToken.value(),
                accessToken.expiresInSeconds(), refreshToken.expiresAt()
        );
    }
}
