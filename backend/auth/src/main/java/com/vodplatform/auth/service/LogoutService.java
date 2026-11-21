package com.vodplatform.auth.service;

import com.vodplatform.auth.persistence.RefreshTokenEntity;
import com.vodplatform.auth.persistence.RefreshTokenRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LogoutService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final Clock clock;

    public LogoutService(
            RefreshTokenRepository refreshTokenRepository,
            RefreshTokenService refreshTokenService,
            Clock clock
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenService = refreshTokenService;
        this.clock = clock;
    }

    @Transactional
    public void logout(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            return;
        }

        String tokenHash = refreshTokenService.hash(value);
        refreshTokenRepository.findByTokenHash(tokenHash)
                .filter(token -> token.getRevokedAt() == null)
                .ifPresent(this::revoke);
    }

    private void revoke(RefreshTokenEntity token) {
        token.revoke(clock.instant());
    }
}
