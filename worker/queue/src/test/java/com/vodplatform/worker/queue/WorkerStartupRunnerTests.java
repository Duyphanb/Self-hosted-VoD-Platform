package com.vodplatform.worker.queue;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.vodplatform.worker.cleanup.WorkerStartupRecovery;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

class WorkerStartupRunnerTests {
    private final WorkerStartupRecovery recovery = mock(WorkerStartupRecovery.class);
    private final SimpleMessageListenerContainer listener = mock(SimpleMessageListenerContainer.class);
    private final WorkerStartupRunner runner = new WorkerStartupRunner(recovery, listener);

    @Test
    void startsOnlyAfterRecovery() {
        runner.run(null);
        var order = inOrder(recovery, listener);
        order.verify(recovery).recover();
        order.verify(listener).start();
    }

    @Test
    void leavesListenerStoppedIfRecoveryCannotFinish() {
        doThrow(new IllegalStateException("Worker startup database recovery could not complete"))
                .when(recovery).recover();
        assertThrows(IllegalStateException.class, () -> runner.run(null));
        verify(listener, never()).start();
    }
}
