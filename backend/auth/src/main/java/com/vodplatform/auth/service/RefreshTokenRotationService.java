package com.vodplatform.auth.service;

import com.vodplatform.auth.dto.AuthResponse;
import com.vodplatform.auth.dto.RefreshRequest;
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
    public AuthResponse rotate(RefreshRequest request) {
        Instant now = clock.instant();
        String tokenHash = refreshTokenService.hash(request.refreshToken());
        RefreshTokenEntity currentToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(InvalidRefreshTokenException::new);
        UserEntity user = currentToken.getUser();

        if (currentToken.getRevokedAt() != null
                || !currentToken.getExpiresAt().isAfter(now)
                || user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidRefreshTokenException();
        }

        currentToken.revoke(now);
        IssuedAccessToken accessToken = jwtAccessTokenService.issue(user);
        IssuedRefreshToken refreshToken = refreshTokenService.issue(user);
        return new AuthResponse(
                userProfileMapper.toProfile(user),
                accessToken.value(),
                refreshToken.value(),
                accessToken.expiresInSeconds()
        );
    }
}
