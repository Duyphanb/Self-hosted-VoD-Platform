package com.vodplatform.auth.service;

import jakarta.persistence.EntityManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.function.LongSupplier;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * One deadline for each login, refresh or logout request, from its arrival to its commit.
 *
 * <p>The SPA aborts these requests after 10 s. A refresh that commits after that rotates a cookie the browser never
 * receives, and its next refresh then counts as a replay and revokes the session. So the whole request gets
 * {@link #BUDGET} from the moment it reaches the application, connection-pool wait included:
 * <ul>
 *   <li>a transaction that starts with less than {@link #MIN_FOR_TRANSACTION} left changes nothing, because it
 *       could not keep {@link #MIN_FOR_COMMIT} for its commit;</li>
 *   <li>PostgreSQL itself cancels any statement of the transaction, a lock wait included, once it has run longer
 *       than the time left minus {@link #MIN_FOR_COMMIT} ({@code SET LOCAL statement_timeout}); the connection
 *       stays usable and no backend is left waiting;</li>
 *   <li>before the commit, the pending changes are flushed under the same rule and the commit's statement timeout
 *       is set; only then is the time left checked a last time, and with less than {@link #MIN_FOR_COMMIT} the
 *       transaction rolls back, so no statement runs between that check and the commit;</li>
 *   <li>the connection's network timeout is only a backstop for a dead connection. It is always set before any
 *       statement it should bound: one second longer than the statement timeout, so PostgreSQL cancels first, and
 *       for the commit the time left at the last check.</li>
 * </ul>
 * Each refusal is a {@link QueryTimeoutException}: a runtime exception, so Spring rolls back explicitly even when
 * it is thrown before the commit, and the auth controllers answer it with 503 {@code AUTH_STATE_UNAVAILABLE}
 * (ADR-012 D6/D7). The statement timeout applies to each statement, so a body with several long lock waits can
 * answer after the deadline; it is still refused at its commit and changes nothing. What remains is a commit whose
 * acknowledgement is lost.
 */
@Component
public class AuthWriteDeadline {

    static final Duration BUDGET = Duration.ofSeconds(6);
    // Longer than any healthy commit.
    static final Duration MIN_FOR_COMMIT = Duration.ofSeconds(2);
    // The commit reserve plus one second for the transaction body.
    static final Duration MIN_FOR_TRANSACTION = MIN_FOR_COMMIT.plusSeconds(1);
    private static final Duration SOCKET_BACKSTOP_MARGIN = Duration.ofSeconds(1);

    private final ThreadLocal<Long> deadlineNanos = new ThreadLocal<>();
    private final EntityManager entityManager;
    private final LongSupplier nanoTime;

    @Autowired
    public AuthWriteDeadline(EntityManager entityManager) {
        this(entityManager, System::nanoTime);
    }

    AuthWriteDeadline(EntityManager entityManager, LongSupplier nanoTime) {
        this.entityManager = entityManager;
        this.nanoTime = nanoTime;
    }

    /** Starts the deadline of the current request; the request filter calls this first. */
    public void startRequest() {
        deadlineNanos.set(nanoTime.getAsLong() + BUDGET.toNanos());
    }

    public void endRequest() {
        deadlineNanos.remove();
    }

    /** Called first in the login, refresh and logout transactions, before they change anything. */
    public void enterTransaction() {
        Long requestDeadline = deadlineNanos.get();
        // Outside a request (for example a test) the deadline starts here and belongs to this transaction only.
        // The transaction keeps the value: its commit may come after the request scope ended.
        long deadline = requestDeadline != null ? requestDeadline : nanoTime.getAsLong() + BUDGET.toNanos();
        Duration left = remaining(deadline);
        if (left.compareTo(MIN_FOR_TRANSACTION) < 0) {
            throw new QueryTimeoutException("The auth request deadline passed before its transaction");
        }
        Duration statementTimeout = left.minus(MIN_FOR_COMMIT);
        limitStatements(statementTimeout);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                // Hibernate would flush only after this callback, so flush here, within the time left.
                Duration leftForFlush = remaining(deadline).minus(MIN_FOR_COMMIT);
                if (leftForFlush.isNegative() || leftForFlush.isZero()) {
                    throw new QueryTimeoutException("The auth request deadline passed before its flush");
                }
                limitStatements(leftForFlush);
                entityManager.flush();
                Duration leftAfterFlush = refuseUnlessCommitReserveLeft(deadline);
                // The commit's own statement timeout is one more round trip, so it comes before the final check.
                limitStatements(leftAfterFlush);
                Duration leftForCommit = refuseUnlessCommitReserveLeft(deadline);
                // Local only: no statement may run between the final check and the commit.
                limitNetwork(leftForCommit);
            }
        });
    }

    private Duration remaining(long deadline) {
        return Duration.ofNanos(deadline - nanoTime.getAsLong());
    }

    private Duration refuseUnlessCommitReserveLeft(long deadline) {
        Duration left = remaining(deadline);
        if (left.compareTo(MIN_FOR_COMMIT) < 0) {
            throw new QueryTimeoutException("The auth request deadline passed before its commit");
        }
        return left;
    }

    // The network backstop goes in first, so the SET LOCAL round trip itself is bounded.
    private void limitStatements(Duration statementTimeout) {
        limitNetwork(statementTimeout.plus(SOCKET_BACKSTOP_MARGIN));
        long statementMillis = Math.max(1, statementTimeout.toMillis());
        entityManager.unwrap(Session.class).doWork(connection -> {
            // SET LOCAL ends with the transaction.
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL statement_timeout = " + statementMillis);
            }
        });
    }

    // Changes only the local socket timeout; it sends nothing to PostgreSQL. HikariCP resets it on return.
    private void limitNetwork(Duration networkTimeout) {
        int networkMillis = (int) Math.max(1, networkTimeout.toMillis());
        entityManager.unwrap(Session.class)
                .doWork(connection -> connection.setNetworkTimeout(Runnable::run, networkMillis));
    }
}
