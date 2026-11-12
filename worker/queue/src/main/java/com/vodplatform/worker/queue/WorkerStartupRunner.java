package com.vodplatform.worker.queue;

import com.vodplatform.worker.cleanup.WorkerStartupRecovery;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public final class WorkerStartupRunner implements ApplicationRunner {
    private final WorkerStartupRecovery recovery;
    private final SimpleMessageListenerContainer listener;

    public WorkerStartupRunner(WorkerStartupRecovery recovery, SimpleMessageListenerContainer listener) {
        this.recovery = recovery;
        this.listener = listener;
    }

    @Override
    public void run(ApplicationArguments args) {
        recovery.recover();
        try {
            listener.start();
        } catch (RuntimeException ignored) {
            throw new IllegalStateException("Worker startup could not start the encoding listener");
        }
    }
}
