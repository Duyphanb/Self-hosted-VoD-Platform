package com.vodplatform.auth.service;

import com.vodplatform.auth.config.AuthTokenProperties;
import com.vodplatform.auth.persistence.AuthSessionEntity;
import com.vodplatform.auth.persistence.AuthSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AuthSessionService {

    private static final int ACTIVE_SESSION_LIMIT = 10;

    private final AuthSessionRepository sessions;
    private final AuthTokenProperties properties;
    private final SessionStateCache cache;

    public AuthSessionService(AuthSessionRepository sessions, AuthTokenProperties properties, SessionStateCache cache) {
        this.sessions = sessions;
        this.properties = properties;
        this.cache = cache;
    }

    // The caller holds the user's row lock, which serializes the active-session count with other logins.
    public AuthSessionEntity start(UUID userId, Instant now) {
        List<AuthSessionEntity> active = sessions.lockActiveByUser(userId, now);
        int evictions = active.size() - ACTIVE_SESSION_LIMIT + 1;
        for (int index = 0; index < evictions; index++) {
            revoke(active.get(index), now);
        }
        Instant expiresAt = now.plus(properties.refreshTokenTtl());
        return sessions.save(new AuthSessionEntity(UUID.randomUUID(), userId, now, expiresAt));
    }

    public Optional<AuthSessionEntity> lock(UUID sessionId, UUID userId) {
        return sessions.lockById(sessionId).filter(session -> session.getUserId().equals(userId));
    }

    public void revoke(AuthSessionEntity session, Instant now) {
        if (session.revoke(now)) {
            invalidateAfterCommit(List.of(session.getId()));
        }
    }

    void invalidateAfterCommit(List<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sessionIds.forEach(cache::invalidate);
            }
        });
    }
}
