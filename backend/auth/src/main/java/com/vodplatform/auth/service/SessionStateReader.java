package com.vodplatform.auth.service;

import com.vodplatform.auth.persistence.AuthSessionRepository;
import com.vodplatform.auth.persistence.SessionState;
import com.vodplatform.auth.security.AuthStateUnavailableException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads the auth state on a dedicated executor, so a request waits at most {@link #timeout()}.
 *
 * <p>A pool wait or a stalled statement then ends as 503 {@code AUTH_STATE_UNAVAILABLE} well before the edge's
 * 60-second read timeout, instead of holding the request thread (ADR-012 D6). The read itself is bounded too:
 * PostgreSQL cancels the statement after 20 s, and the connection's network timeout ends a read on a dead
 * connection after 25 s, so a worker always comes back.
 */
@Service
public class SessionStateReader {

    // Each result authorizes for 30 s from the start of its read, so a read must end well within that window.
    private static final Duration MIN_TIMEOUT = Duration.ofMillis(1);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(20);
    // Longer than the 20-second statement timeout of readState: a live server cancels the statement first.
    private static final int NETWORK_TIMEOUT_MILLIS = 25_000;

    private final StoreRead boundedRead;
    private final StoreRead inlineRead;
    private final Executor executor;
    private final Duration timeout;

    @Autowired
    public SessionStateReader(
            AuthSessionRepository sessions,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager,
            @Qualifier("authStateReadExecutor") Executor executor,
            @Value("${auth.sessions.state-read-timeout:5s}") Duration timeout
    ) {
        this(
                boundedConnectionRead(sessions, entityManager, transactionManager),
                sessions::readState,
                executor,
                timeout
        );
    }

    SessionStateReader(StoreRead boundedRead, StoreRead inlineRead, Executor executor, Duration timeout) {
        if (timeout.compareTo(MIN_TIMEOUT) < 0 || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalStateException("auth.sessions.state-read-timeout must be between 1 ms and 20 s");
        }
        this.boundedRead = boundedRead;
        this.inlineRead = inlineRead;
        this.executor = executor;
        this.timeout = timeout;
    }

    public Duration timeout() {
        return timeout;
    }

    /** Reads and waits at most {@link #timeout()}; a read that is still queued then never runs. */
    public Optional<SessionState> read(UUID id, boolean readAdminRole) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // The caller already holds a connection and may read its own uncommitted rows, which another
            // thread could not see. The security filter runs before any transaction, so requests never get here.
            return mapped(() -> inlineRead.read(id, readAdminRole));
        }
        CompletableFuture<Optional<SessionState>> pending = readAsync(id, readAdminRole);
        try {
            return pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException slow) {
            pending.cancel(false);
            throw new AuthStateUnavailableException(slow);
        } catch (InterruptedException interrupted) {
            pending.cancel(false);
            Thread.currentThread().interrupt();
            throw new AuthStateUnavailableException(interrupted);
        } catch (ExecutionException failed) {
            throw rethrown(failed.getCause());
        }
    }

    /**
     * Starts one physical read and completes when it ends, however long the caller waits.
     *
     * <p>Cancelling the future before a worker takes the read means it never runs. A read still queued after
     * {@link #timeout()} is retired unread: every caller has stopped waiting, and after a stall it would only
     * delay fresh reads.
     */
    public CompletableFuture<Optional<SessionState>> readAsync(UUID id, boolean readAdminRole) {
        var result = new CompletableFuture<Optional<SessionState>>();
        try {
            long queuedAt = System.nanoTime();
            executor.execute(() -> runRead(result, queuedAt, id, readAdminRole));
        } catch (RejectedExecutionException saturated) {
            result.completeExceptionally(new AuthStateUnavailableException(saturated));
        }
        return result;
    }

    private void runRead(
            CompletableFuture<Optional<SessionState>> result,
            long queuedAt,
            UUID id,
            boolean readAdminRole
    ) {
        if (result.isDone()) {
            return;
        }
        if (System.nanoTime() - queuedAt > timeout.toNanos()) {
            result.completeExceptionally(new AuthStateUnavailableException(null));
            return;
        }
        try {
            result.complete(mapped(() -> boundedRead.read(id, readAdminRole)));
        } catch (RuntimeException | Error failure) {
            result.completeExceptionally(failure);
        }
    }

    private static StoreRead boundedConnectionRead(
            AuthSessionRepository sessions,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager
    ) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        return (id, readAdminRole) -> transaction.execute(status -> {
            // The query below runs on this transaction's connection. HikariCP resets the timeout on return.
            entityManager.unwrap(Session.class)
                    .doWork(connection -> connection.setNetworkTimeout(Runnable::run, NETWORK_TIMEOUT_MILLIS));
            return sessions.readState(id, readAdminRole);
        });
    }

    private static Optional<SessionState> mapped(StoreCall call) {
        try {
            return call.run();
        } catch (DataAccessException | TransactionException | PersistenceException unavailable) {
            throw new AuthStateUnavailableException(unavailable);
        }
    }

    private static RuntimeException rethrown(Throwable cause) {
        if (cause instanceof CompletionException || cause instanceof CancellationException) {
            return new AuthStateUnavailableException(cause);
        }
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new AuthStateUnavailableException(cause);
    }

    @FunctionalInterface
    interface StoreRead {
        Optional<SessionState> read(UUID id, boolean readAdminRole);
    }

    @FunctionalInterface
    private interface StoreCall {
        Optional<SessionState> run();
    }
}
