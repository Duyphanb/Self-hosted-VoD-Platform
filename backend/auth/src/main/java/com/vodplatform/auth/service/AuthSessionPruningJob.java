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

    @Scheduled(cron = "${auth.sessions.prune-cron:0 15 3 * * *}", zone = "UTC", scheduler = "authPruningScheduler")
    public void prune() {
        Instant cutoff = clock.instant().minus(RETENTION);
        while (Boolean.TRUE.equals(transactions.execute(status -> pruneEndedSessions(cutoff)))) {
            // Commit each batch before getting the next one.
        }
        while (transactions.execute(status -> pruneLegacyTokens()) == BATCH_SIZE) {
            // Legacy token rows have no session to cascade from. A full batch means more rows may remain.
        }
    }

    private boolean pruneEndedSessions(Instant cutoff) {
        List<UUID> locked = jdbc.sql("""
                SELECT id FROM auth_sessions WHERE LEAST(expires_at, revoked_at) < :cutoff
                ORDER BY LEAST(expires_at, revoked_at), id
                LIMIT :limit FOR UPDATE SKIP LOCKED
                """).param("cutoff", Timestamp.from(cutoff)).param("limit", BATCH_SIZE).query(UUID.class).list();
        if (locked.isEmpty()) {
            return false;
        }

        // No ORDER BY: any 500 tokens will do. The query returns at most 500 rows and never sorts every remaining
        // token of a high-fan-out session.
        List<UUID> tokenBatch = jdbc.sql("""
                SELECT id FROM refresh_tokens WHERE session_id IN (:sessionIds) LIMIT :limit
                """).param("sessionIds", locked).param("limit", BATCH_SIZE).query(UUID.class).list();
        if (!tokenBatch.isEmpty()) {
            deleteTokens(tokenBatch);
            return true;
        }

        // We still hold FOR UPDATE on every parent, so no new FK reference can appear before the delete, and the
        // ON DELETE CASCADE from auth_sessions adds no token rows to this transaction.
        jdbc.sql("DELETE FROM auth_sessions WHERE id IN (:sessionIds)").param("sessionIds", locked).update();
        sessions.invalidateAfterCommit(locked);
        return true;
    }

    private int pruneLegacyTokens() {
        List<UUID> tokenBatch = jdbc.sql("""
                SELECT id FROM refresh_tokens WHERE session_id IS NULL
                ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED
                """).param("limit", BATCH_SIZE).query(UUID.class).list();
        if (!tokenBatch.isEmpty()) {
            deleteTokens(tokenBatch);
        }
        return tokenBatch.size();
    }

    // Each batch is read first and then deleted by its exact IDs. PostgreSQL does not promise that
    // DELETE ... WHERE id IN (SELECT ... LIMIT n) removes at most n rows: the plan may evaluate the subquery more than
    // once, and a later evaluation can select other rows. CI saw one prune transaction delete 501 rows (#269).
    private void deleteTokens(List<UUID> tokenIds) {
        jdbc.sql("DELETE FROM refresh_tokens WHERE id IN (:tokenIds)").param("tokenIds", tokenIds).update();
    }
}
