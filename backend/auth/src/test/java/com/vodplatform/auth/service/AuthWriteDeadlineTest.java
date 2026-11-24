package com.vodplatform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.Session;
import org.hibernate.jdbc.Work;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AuthWriteDeadlineTest {

    private final AtomicLong nanos = new AtomicLong();
    private final List<Integer> networkTimeouts = new CopyOnWriteArrayList<>();
    private final List<Long> statementTimeouts = new CopyOnWriteArrayList<>();
    private final List<String> connectionCalls = new CopyOnWriteArrayList<>();
    private Duration flushTakes = Duration.ZERO;
    private Duration statementTakes = Duration.ZERO;
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
    void postgresCancelsEachStatementBeforeTheCommitReserveAndTheSocketWaitsLonger() {
        deadline.startRequest();
        advance(Duration.ofSeconds(2));

        deadline.enterTransaction();
        advance(Duration.ofSeconds(1));
        beforeCommit();

        // Entry with 4 s left; flush with 3 s left; commit with 3 s left.
        assertThat(statementTimeouts).containsExactly(2_000L, 1_000L, 3_000L);
        assertThat(networkTimeouts).containsExactly(3_000, 2_000, 4_000, 3_000);
    }

    @Test
    void theNetworkTimeoutIsInPlaceBeforeEveryStatementItBounds() {
        deadline.startRequest();
        deadline.enterTransaction();
        beforeCommit();

        assertThat(connectionCalls).containsExactly(
                "network", "statement",
                "network", "statement",
                "network", "statement",
                "network");
    }

    @Test
    void theCommitStatementTimeoutRoundTripCountsBeforeTheFinalCheck() {
        deadline.startRequest();
        deadline.enterTransaction();
        advance(Duration.ofSeconds(2));
        statementTakes = Duration.ofMillis(1_200);

        // 4 s left; the flush's SET LOCAL leaves 2.8 s, the commit's SET LOCAL 1.6 s: under the 2 s reserve.
        assertThatThrownBy(this::beforeCommit).isInstanceOf(QueryTimeoutException.class);
        assertThat(connectionCalls).endsWith("network", "statement");
    }

    @Test
    void theCommitNetworkTimeoutIsTheTimeLeftAfterTheLastStatement() {
        deadline.startRequest();
        deadline.enterTransaction();
        advance(Duration.ofSeconds(1));
        statementTakes = Duration.ofMillis(500);

        beforeCommit();

        // 5 s left; 4.5 s after the flush's SET LOCAL; 4 s after the commit's SET LOCAL.
        assertThat(networkTimeouts).endsWith(4_000);
    }

    @Test
    void aTransactionThatGotItsConnectionTooLateChangesNothing() {
        deadline.startRequest();
        // For example a connection-pool wait that outlasted the request budget.
        advance(Duration.ofMillis(5_001));

        assertThatThrownBy(deadline::enterTransaction).isInstanceOf(QueryTimeoutException.class);
        assertThat(statementTimeouts).isEmpty();
        assertThat(networkTimeouts).isEmpty();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void aTransactionThatCouldNotKeepItsCommitReserveIsRefusedAtTheStart() {
        deadline.startRequest();
        // 2.5 s left: enough for neither a body nor the 2 s commit reserve after it.
        advance(Duration.ofMillis(3_500));

        assertThatThrownBy(deadline::enterTransaction).isInstanceOf(QueryTimeoutException.class);
        assertThat(statementTimeouts).isEmpty();
    }

    @Test
    void aTransactionReachingItsFlushTooLateIsRefusedWithoutFlushing() {
        deadline.startRequest();
        deadline.enterTransaction();
        advance(Duration.ofMillis(4_001));
        flushTakes = Duration.ofHours(1);

        assertThatThrownBy(this::beforeCommit).isInstanceOf(QueryTimeoutException.class);
        assertThat(nanos.get()).isEqualTo(Duration.ofMillis(4_001).toNanos());
        assertThat(statementTimeouts).containsExactly(4_000L);
    }

    @Test
    void theTimeTheFlushTakesCountsBeforeTheCommit() {
        deadline.startRequest();
        deadline.enterTransaction();
        advance(Duration.ofSeconds(3));
        flushTakes = Duration.ofMillis(1_500);

        // 3 s were left before the flush, 1.5 s after it: less than the 2 s kept for the commit.
        assertThatThrownBy(this::beforeCommit).isInstanceOf(QueryTimeoutException.class);
        assertThat(statementTimeouts).containsExactly(4_000L, 1_000L);
    }

    @Test
    void outsideARequestEachTransactionGetsItsOwnDeadline() {
        advance(Duration.ofHours(1));
        deadline.enterTransaction();
        advance(Duration.ofSeconds(7));

        deadline.enterTransaction();

        assertThat(statementTimeouts).containsExactly(4_000L, 4_000L);
    }

    private void beforeCommit() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.beforeCommit(false);
        }
    }

    private static long statementTimeoutOf(String sql) {
        String prefix = "SET LOCAL statement_timeout = ";
        assertThat(sql).startsWith(prefix);
        return Long.parseLong(sql.substring(prefix.length()));
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    private EntityManager entityManagerRecordingTimeouts() {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        try {
            doAnswer(invocation -> {
                connectionCalls.add("network");
                return networkTimeouts.add(invocation.getArgument(1));
            }).when(connection).setNetworkTimeout(any(), anyInt());
            when(connection.createStatement()).thenReturn(statement);
            doAnswer(invocation -> {
                connectionCalls.add("statement");
                statementTimeouts.add(statementTimeoutOf(invocation.getArgument(0)));
                advance(statementTakes);
                return false;
            }).when(statement).execute(anyString());
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
        doAnswer(invocation -> {
            advance(flushTakes);
            return null;
        }).when(entityManager).flush();
        return entityManager;
    }
}
