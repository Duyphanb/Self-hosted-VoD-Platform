package com.vodplatform.catalog.service;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A transaction manager without a resource, for tests without a database. It manages transaction
 * state like any Spring transaction manager (a write it runs sees an active transaction, and a
 * nested {@code REQUIRED} transaction joins the outer one) and counts the transactions it began,
 * committed and rolled back.
 */
final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

    final AtomicInteger begun = new AtomicInteger();
    final AtomicInteger committed = new AtomicInteger();
    final AtomicInteger rolledBack = new AtomicInteger();

    /** Bound to the thread while one of this manager's transactions runs. */
    private final Object resourceKey = new Object();

    @Override
    protected Object doGetTransaction() {
        return new Transaction(TransactionSynchronizationManager.hasResource(resourceKey));
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
        return ((Transaction) transaction).existing;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        TransactionSynchronizationManager.bindResource(resourceKey, transaction);
        begun.incrementAndGet();
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        committed.incrementAndGet();
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        rolledBack.incrementAndGet();
    }

    @Override
    protected void doSetRollbackOnly(DefaultTransactionStatus status) {
        // Nothing to mark: the outer transaction decides how it ends.
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        TransactionSynchronizationManager.unbindResource(resourceKey);
    }

    private record Transaction(boolean existing) {
    }
}
