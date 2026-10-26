package com.vodplatform.catalog.service;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a catalog write in a transaction of its own and, when the database aborts that transaction
 * as a deadlock victim ({@code SQLSTATE 40P01}) or with a serialization failure ({@code 40001}),
 * runs the whole write again in a new transaction, up to {@value #MAX_ATTEMPTS} attempts in all.
 *
 * <p>Inserting new genres and people in slug order ({@link MovieAssignmentResolver}) removes the
 * common deadlock between two movie writes, but no fixed order covers every case: the person slug
 * actually written depends on the suffixes taken when it is written, so two writes can still insert
 * the same slugs in opposite orders, and an admin rename locks the renamed row before it waits for
 * a slug that a movie write holds, while that movie write needs the same row for its credit or
 * genre reference. PostgreSQL breaks such a cycle by aborting one transaction; the retry turns
 * that abort into a short wait.
 *
 * <p>Each attempt is a new transaction with a new persistence context (open-in-view is off), so
 * nothing the failed attempt read or wrote carries over: a write must start from its validated
 * input and load what it changes inside the attempt. Only a failure whose cause chain holds one of
 * those SQLSTATEs is retried, after a short random pause; validation, not-found and conflict
 * outcomes and every other failure propagate from the first attempt. When the last attempt fails
 * too, its failure propagates unchanged after a single warning, which names the SQLSTATE but no
 * request data.
 *
 * <p>The retry needs a transaction that it starts itself. A caller that already runs in a
 * transaction gets a single attempt that joins it: the database aborts the caller's whole
 * transaction, which a retry here could not restart.
 */
@Component
class CatalogWriteRetry {

    /** Attempts per write, the first included. */
    static final int MAX_ATTEMPTS = 3;

    /** Upper bound of the pause after the first failed attempt; it grows with each failure. */
    static final long BACKOFF_MILLIS = 50;

    private static final Set<String> RETRYABLE_SQL_STATES = Set.of(
            "40P01", // deadlock_detected
            "40001"  // serialization_failure; H2 also reports its deadlocks with it
    );

    private static final Logger log = LoggerFactory.getLogger(CatalogWriteRetry.class);

    private final TransactionTemplate transactionTemplate;

    CatalogWriteRetry(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Runs the write in a new transaction and returns its result, running it again in another new
     * transaction after a deadlock or serialization failure; see the class comment.
     *
     * @param write the whole write, from loading what it changes to building its result
     */
    <T> T execute(Supplier<T> write) {
        Objects.requireNonNull(write, "write");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return transactionTemplate.execute(status -> write.get());
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> write.get());
            } catch (RuntimeException failure) {
                String sqlState = retryableSqlState(failure);
                if (sqlState == null) {
                    throw failure;
                }
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("Catalog write failed: the database aborted all {} attempts (SQLSTATE {})",
                            MAX_ATTEMPTS, sqlState);
                    throw failure;
                }
                log.debug("Catalog write attempt {} of {} aborted by the database (SQLSTATE {}); retrying",
                        attempt, MAX_ATTEMPTS, sqlState);
                if (!pauseBeforeRetry(attempt)) {
                    log.warn("Catalog write failed: interrupted after attempt {} of {} (SQLSTATE {})",
                            attempt, MAX_ATTEMPTS, sqlState);
                    throw failure;
                }
            }
        }
    }

    /**
     * Returns the SQLSTATE of the first deadlock or serialization failure in the cause chain of the
     * failure, following the next-exception chain of each {@link SQLException} as well (a batch
     * failure reports the statement's error there), or null when there is none.
     */
    static String retryableSqlState(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.push(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.pop();
            if (!seen.add(current)) {
                continue;
            }
            if (current instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                if (sqlState != null && RETRYABLE_SQL_STATES.contains(sqlState)) {
                    return sqlState;
                }
                if (sqlException.getNextException() != null) {
                    pending.push(sqlException.getNextException());
                }
            }
            if (current.getCause() != null) {
                pending.push(current.getCause());
            }
        }
        return null;
    }

    /**
     * Pauses for a random time up to {@code BACKOFF_MILLIS} times the number of failed attempts,
     * so that the transactions of a deadlock do not meet again in step. Returns false, with the
     * interrupt flag restored, when the thread is interrupted.
     */
    private static boolean pauseBeforeRetry(int failedAttempts) {
        long ceiling = BACKOFF_MILLIS * failedAttempts;
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(ceiling / 2, ceiling + 1));
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
