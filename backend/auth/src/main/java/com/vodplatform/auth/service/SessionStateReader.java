package com.vodplatform.auth.service;

import com.vodplatform.auth.persistence.AuthSessionRepository;
import com.vodplatform.auth.persistence.SessionState;
import com.vodplatform.auth.security.AuthStateUnavailableException;
import jakarta.persistence.PersistenceException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Reads the auth state on a dedicated executor, so a request waits at most {@link #timeout()}.
 *
 * <p>A pool wait or a stalled statement then ends as 503 {@code AUTH_STATE_UNAVAILABLE} well before the edge's
 * 60-second read timeout, instead of holding the request thread (ADR-012 D6).
 */
@Service
public class SessionStateReader {

    // Each result authorizes for 30 s from the start of its read, so a read must end well within that window.
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(20);

    private final AuthSessionRepository sessions;
    private final Executor executor;
    private final Duration timeout;

    public SessionStateReader(
            AuthSessionRepository sessions,
            @Qualifier("authStateReadExecutor") Executor executor,
            @Value("${auth.sessions.state-read-timeout:10s}") Duration timeout
    ) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalStateException("auth.sessions.state-read-timeout must be between 1 ms and 20 s");
        }
        this.sessions = sessions;
        this.executor = executor;
        this.timeout = timeout;
    }

    public Duration timeout() {
        return timeout;
    }

    public Optional<SessionState> read(UUID id, boolean readAdminRole) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // The caller already holds a connection and may read its own uncommitted rows, which another
            // thread could not see. The security filter runs before any transaction, so requests never get here.
            return readFromStore(id, readAdminRole);
        }
        var task = new FutureTask<>(() -> readFromStore(id, readAdminRole));
        try {
            executor.execute(task);
        } catch (RejectedExecutionException saturated) {
            throw new AuthStateUnavailableException(saturated);
        }
        try {
            return task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException slow) {
            // A queued read never starts; a running one ends at its statement timeout.
            task.cancel(false);
            throw new AuthStateUnavailableException(slow);
        } catch (InterruptedException interrupted) {
            task.cancel(false);
            Thread.currentThread().interrupt();
            throw new AuthStateUnavailableException(interrupted);
        } catch (ExecutionException failed) {
            throw rethrown(failed.getCause());
        }
    }

    private Optional<SessionState> readFromStore(UUID id, boolean readAdminRole) {
        try {
            return sessions.readState(id, readAdminRole);
        } catch (DataAccessException | TransactionException | PersistenceException unavailable) {
            throw new AuthStateUnavailableException(unavailable);
        }
    }

    private static RuntimeException rethrown(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new AuthStateUnavailableException(cause);
    }
}
