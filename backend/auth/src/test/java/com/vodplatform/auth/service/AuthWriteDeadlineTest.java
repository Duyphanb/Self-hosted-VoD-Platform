package com.vodplatform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.Session;
import org.hibernate.jdbc.Work;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AuthWriteDeadlineTest {

    private final AtomicLong nanos = new AtomicLong();
    private final List<Integer> networkTimeouts = new CopyOnWriteArrayList<>();
    private final AuthWriteDeadline deadline = new AuthWriteDeadline(entityManagerRecordingTimeouts(), nanos::get);

    @BeforeEach
    void startTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearTransactionSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
        deadline.endRequest();
    }

    @Test
    void aTransactionStartingInTimeLimitsEachSocketWaitToTheTimeLeft() {
        deadline.startRequest();
        advance(Duration.ofSeconds(2));

        deadline.enterTransaction();
        advance(Duration.ofSeconds(1));
        beforeCommit();

        assertThat(networkTimeouts).containsExactly(4_000, 3_000);
    }

    @Test
    void aTransactionThatGotItsConnectionTooLateChangesNothing() {
        deadline.startRequest();
        // For example a connection-pool wait that outlasted the request budget.
        advance(Duration.ofMillis(5_001));

        assertThatThrownBy(deadline::enterTransaction).isInstanceOf(TransactionTimedOutException.class);
        assertThat(networkTimeouts).isEmpty();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void aTransactionReachingItsCommitTooLateRollsBack() {
        deadline.startRequest();
        deadline.enterTransaction();
        advance(Duration.ofMillis(5_501));

        assertThatThrownBy(this::beforeCommit).isInstanceOf(TransactionTimedOutException.class);
        assertThat(networkTimeouts).containsExactly(6_000);
    }

    @Test
    void outsideARequestTheDeadlineStartsWithTheTransaction() {
        advance(Duration.ofHours(1));

        deadline.enterTransaction();

        assertThat(networkTimeouts).containsExactly(6_000);
    }

    private void beforeCommit() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.beforeCommit(false);
        }
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    private EntityManager entityManagerRecordingTimeouts() {
        Connection connection = mock(Connection.class);
        try {
            doAnswer(invocation -> networkTimeouts.add(invocation.getArgument(1)))
                    .when(connection).setNetworkTimeout(any(), anyInt());
        } catch (SQLException impossible) {
            throw new AssertionError(impossible);
        }
        Session session = mock(Session.class);
        doAnswer(invocation -> {
            invocation.<Work>getArgument(0).execute(connection);
            return null;
        }).when(session).doWork(any());
        EntityManager entityManager = mock(EntityManager.class);
        when(entityManager.unwrap(Session.class)).thenReturn(session);
        return entityManager;
    }
}
