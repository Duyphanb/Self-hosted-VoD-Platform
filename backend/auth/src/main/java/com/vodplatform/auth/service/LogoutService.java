package com.vodplatform.auth.service;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LogoutService {

    private final AuthSessionService sessions;
    private final RefreshTokenService refreshTokenService;
    private final Clock clock;

    public LogoutService(
            AuthSessionService sessions,
            RefreshTokenService refreshTokenService,
            Clock clock
    ) {
        this.sessions = sessions;
        this.refreshTokenService = refreshTokenService;
        this.clock = clock;
    }

    @Transactional(timeout = AuthTransactions.TIMEOUT_SECONDS)
    public void logout(String value) {
        sessions.limitTransactionConnection();
        refreshTokenService.locate(value)
                .flatMap(location -> sessions.lock(location.sessionId(), location.userId()))
                .ifPresent(session -> sessions.revoke(session, clock.instant()));
    }
}
