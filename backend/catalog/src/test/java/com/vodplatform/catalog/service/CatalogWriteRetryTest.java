package com.vodplatform.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.vodplatform.catalog.exception.CatalogValidationException;
import com.vodplatform.catalog.exception.GenreConflictException;
import com.vodplatform.catalog.exception.MovieNotFoundException;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.LockAcquisitionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Which failures {@link CatalogWriteRetry} retries, how often, and in which transactions, without
 * a database. {@link CatalogWriteRetryTransactionTest} covers the transactions on H2.
 */
class CatalogWriteRetryTest {

    private final NoOpTransactionManager transactionManager = new NoOpTransactionManager();
    private final CatalogWriteRetry writeRetry = new CatalogWriteRetry(transactionManager);

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void aDeadlockIsRetriedInANewTransactionUntilTheWriteSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        List<Boolean> activeTransactions = new ArrayList<>();

        String result = writeRetry.execute(() -> {
            activeTransactions.add(TransactionSynchronizationManager.isActualTransactionActive());
            if (attempts.incrementAndGet() < CatalogWriteRetry.MAX_ATTEMPTS) {
                throw deadlock();
            }
            return "written";
        });

        assertThat(result).isEqualTo("written");
        assertThat(attempts).hasValue(CatalogWriteRetry.MAX_ATTEMPTS);
        assertThat(activeTransactions).containsOnly(true);
        assertThat(transactionManager.begun).hasValue(CatalogWriteRetry.MAX_ATTEMPTS);
        assertThat(transactionManager.rolledBack).hasValue(CatalogWriteRetry.MAX_ATTEMPTS - 1);
        assertThat(transactionManager.committed).hasValue(1);
    }

    @Test
    void aSerializationFailureIsRetried() {
        AtomicInteger attempts = new AtomicInteger();

        String result = writeRetry.execute(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new PessimisticLockingFailureException("could not serialize",
                        new LockAcquisitionException("could not execute statement",
                                new SQLException("could not serialize access", "40001")));
            }
            return "written";
        });

        assertThat(result).isEqualTo("written");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void theStateOfABatchFailureIsFoundInItsNextException() {
        AtomicInteger attempts = new AtomicInteger();

        writeRetry.execute(() -> {
            if (attempts.incrementAndGet() == 1) {
                BatchUpdateException batch = new BatchUpdateException("batch entry 0 failed", null, new int[0]);
                batch.setNextException(new SQLException("deadlock detected", "40P01"));
                throw new CannotAcquireLockException("could not execute batch", batch);
            }
            return null;
        });

        assertThat(attempts).hasValue(2);
    }

    @Test
    void theLastDeadlockPropagatesUnchangedWhenEveryAttemptFails() {
        AtomicInteger attempts = new AtomicInteger();
        List<RuntimeException> thrown = new ArrayList<>();

        Throwable failure = catchThrowable(() -> writeRetry.execute(() -> {
            attempts.incrementAndGet();
            RuntimeException deadlock = deadlock();
            thrown.add(deadlock);
            throw deadlock;
        }));

        assertThat(attempts).hasValue(CatalogWriteRetry.MAX_ATTEMPTS);
        assertThat(failure).isSameAs(thrown.getLast());
        assertThat(transactionManager.rolledBack).hasValue(CatalogWriteRetry.MAX_ATTEMPTS);
        assertThat(transactionManager.committed).hasValue(0);
    }

    @ParameterizedTest
    @MethodSource("failuresThatAreNotRetried")
    void otherFailuresPropagateFromTheFirstAttempt(RuntimeException other) {
        AtomicInteger attempts = new AtomicInteger();

        Throwable failure = catchThrowable(() -> writeRetry.execute(() -> {
            attempts.incrementAndGet();
            throw other;
        }));

        assertThat(failure).isSameAs(other);
        assertThat(attempts).hasValue(1);
        assertThat(transactionManager.rolledBack).hasValue(1);
    }

    static Stream<RuntimeException> failuresThatAreNotRetried() {
        return Stream.of(
                new MovieNotFoundException(),
                new GenreConflictException(),
                new CatalogValidationException(List.of()),
                new IllegalStateException("No free person slug after 10 attempts"),
                new DataIntegrityViolationException("duplicate key", new ConstraintViolationException(
                        "duplicate key", new SQLException("duplicate key", "23505"), "ux_genres_slug")),
                // A lock wait that timed out (lock_not_available) is not a deadlock.
                new CannotAcquireLockException("lock timeout", new SQLException("could not obtain lock", "55P03")),
                new CannotAcquireLockException("no state", new SQLException("no state")),
                new PessimisticLockingFailureException("no cause")
        );
    }

    @Test
    void aWriteInsideACallerTransactionJoinsItAndRunsOnce() {
        AtomicInteger attempts = new AtomicInteger();
        RuntimeException deadlock = deadlock();

        Throwable failure = catchThrowable(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> writeRetry.execute(() -> {
                    attempts.incrementAndGet();
                    throw deadlock;
                })));

        assertThat(failure).isSameAs(deadlock);
        assertThat(attempts).hasValue(1);
        // Only the caller's transaction was begun; the write joined it.
        assertThat(transactionManager.begun).hasValue(1);
    }

    @Test
    void anInterruptedRetryPropagatesTheDeadlockAndKeepsTheInterrupt() {
        AtomicInteger attempts = new AtomicInteger();
        RuntimeException deadlock = deadlock();
        Thread.currentThread().interrupt();

        Throwable failure = catchThrowable(() -> writeRetry.execute(() -> {
            attempts.incrementAndGet();
            throw deadlock;
        }));

        assertThat(failure).isSameAs(deadlock);
        assertThat(attempts).hasValue(1);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void theRetryableStateIsFoundAnywhereInTheCauseChain() {
        assertThat(CatalogWriteRetry.retryableSqlState(deadlock())).isEqualTo("40P01");
        assertThat(CatalogWriteRetry.retryableSqlState(new RuntimeException(new RuntimeException(
                new SQLException("could not serialize access", "40001"))))).isEqualTo("40001");
        assertThat(CatalogWriteRetry.retryableSqlState(new RuntimeException("no cause"))).isNull();

        RuntimeException cyclic = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", cyclic);
        cyclic.initCause(second);
        assertThat(CatalogWriteRetry.retryableSqlState(cyclic)).isNull();
    }

    /**
     * A deadlock as Spring reports one from Hibernate on PostgreSQL.
     */
    static RuntimeException deadlock() {
        return new CannotAcquireLockException("could not execute statement",
                new LockAcquisitionException("could not execute statement",
                        new SQLException("deadlock detected", "40P01")));
    }
}
