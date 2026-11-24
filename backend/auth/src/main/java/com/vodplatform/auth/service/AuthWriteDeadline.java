package com.vodplatform.auth.service;

import jakarta.persistence.EntityManager;
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
 *   <li>a transaction that starts with less than {@link #MIN_FOR_TRANSACTION} left changes nothing;</li>
 *   <li>before the commit, the pending changes are flushed within the time left; with less than
 *       {@link #MIN_FOR_COMMIT} left after that flush, the transaction rolls back;</li>
 *   <li>the connection's network timeout is always the time left, so no single socket wait can outlast the
 *       deadline, the flush and the commit included.</li>
 * </ul>
 * Each refusal is a {@link QueryTimeoutException}: a runtime exception, so Spring rolls back explicitly even when
 * it is thrown before the commit, and the auth controllers answer it with 503 {@code AUTH_STATE_UNAVAILABLE}
 * (ADR-012 D6/D7). What remains is a commit whose acknowledgement is lost.
 */
@Component
public class AuthWriteDeadline {

    static final Duration BUDGET = Duration.ofSeconds(6);
    static final Duration MIN_FOR_TRANSACTION = Duration.ofSeconds(1);
    // Longer than any healthy commit, and short enough that a slow final flush is refused before the 5-second
    // statement timeout cancels it.
    static final Duration MIN_FOR_COMMIT = Duration.ofSeconds(2);

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
        limitNetworkWait(left);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                // Hibernate would flush only after this callback, so flush here, within the time left.
                limitNetworkWait(remaining(deadline));
                entityManager.flush();
                Duration leftForCommit = remaining(deadline);
                if (leftForCommit.compareTo(MIN_FOR_COMMIT) < 0) {
                    throw new QueryTimeoutException("The auth request deadline passed before its commit");
                }
                limitNetworkWait(leftForCommit);
            }
        });
    }

    private Duration remaining(long deadline) {
        return Duration.ofNanos(deadline - nanoTime.getAsLong());
    }

    private void limitNetworkWait(Duration left) {
        int millis = (int) Math.max(1, left.toMillis());
        // HikariCP resets the network timeout when the connection returns to the pool.
        entityManager.unwrap(Session.class).doWork(connection -> connection.setNetworkTimeout(Runnable::run, millis));
    }
}
