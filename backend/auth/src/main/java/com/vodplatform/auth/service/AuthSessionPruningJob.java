package com.vodplatform.auth.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AuthSessionPruningJob {

    private static final int BATCH_SIZE = 500;
    private static final Duration RETENTION = Duration.ofDays(30);

    private final JdbcClient jdbc;
    private final AuthSessionService sessions;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AuthSessionPruningJob(JdbcClient jdbc, AuthSessionService sessions,
                                 PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "UTC")
    public void prune() {
        Instant cutoff = clock.instant().minus(RETENTION);
        while (transactions.execute(status -> pruneSessions(cutoff)) == BATCH_SIZE) {
            // Commit each batch before getting the next one.
        }
        while (transactions.execute(status -> pruneLegacyTokens()) == BATCH_SIZE) {
            // Legacy token rows have no session to cascade from.
        }
    }

    private int pruneSessions(Instant cutoff) {
        List<UUID> removed = jdbc.sql("""
                DELETE FROM auth_sessions WHERE id IN (
                    SELECT id FROM auth_sessions WHERE LEAST(expires_at, revoked_at) < :cutoff
                    ORDER BY LEAST(expires_at, revoked_at), id
                    LIMIT :limit FOR UPDATE SKIP LOCKED
                ) RETURNING id
                """).param("cutoff", Timestamp.from(cutoff)).param("limit", BATCH_SIZE).query(UUID.class).list();
        sessions.invalidateAfterCommit(removed);
        return removed.size();
    }

    private int pruneLegacyTokens() {
        return jdbc.sql("""
                DELETE FROM refresh_tokens WHERE id IN (
                    SELECT id FROM refresh_tokens WHERE session_id IS NULL
                    ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED
                )
                """).param("limit", BATCH_SIZE).update();
    }
}
