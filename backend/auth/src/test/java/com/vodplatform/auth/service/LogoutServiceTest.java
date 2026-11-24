package com.vodplatform.auth.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.vodplatform.auth.persistence.AuthSessionEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LogoutServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

    private final AuthSessionService sessions = mock(AuthSessionService.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final LogoutService service = new LogoutService(
            sessions,
            refreshTokenService,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void knownTokensSessionIsRevokedAtCurrentInstant() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        var session = new AuthSessionEntity(sessionId, userId, NOW.minusSeconds(60), NOW.plusSeconds(60));
        var location = new RefreshTokenService.LocatedToken("stored-hash", userId, sessionId);
        when(refreshTokenService.locate("raw-token")).thenReturn(Optional.of(location));
        when(sessions.lock(sessionId, userId)).thenReturn(Optional.of(session));

        service.logout("raw-token");

        verify(sessions).revoke(session, NOW);
    }

    @Test
    void omittedTokenIsAnIdempotentNoOp() {
        service.logout(null);

        verify(sessions).limitTransactionConnection();
        verifyNoMoreInteractions(sessions);
    }

    @Test
    void unknownTokenIsAnIdempotentNoOp() {
        when(refreshTokenService.locate("unknown-token")).thenReturn(Optional.empty());

        service.logout("unknown-token");

        verify(sessions).limitTransactionConnection();
        verifyNoMoreInteractions(sessions);
    }

    @Test
    void sessionDeletedByPruningIsAnIdempotentNoOp() {
        var location = new RefreshTokenService.LocatedToken("stored-hash", UUID.randomUUID(), UUID.randomUUID());
        when(refreshTokenService.locate("raw-token")).thenReturn(Optional.of(location));

        service.logout("raw-token");

        verify(sessions).limitTransactionConnection();
        verify(sessions).lock(location.sessionId(), location.userId());
        verifyNoMoreInteractions(sessions);
    }
}
