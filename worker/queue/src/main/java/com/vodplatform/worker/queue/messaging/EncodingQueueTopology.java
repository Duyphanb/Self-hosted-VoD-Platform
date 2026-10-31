package com.vodplatform.worker.queue.messaging;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

/** ADR-010 classic topology. Keep the worker factory identical; never repair a live mismatch. */
public final class EncodingQueueTopology {
    private EncodingQueueTopology() { }

    public static String validateQueueName(String name) {
        if (name == null || name.length() > 251 || !name.matches("[A-Za-z0-9._-]+") || name.startsWith("amq.")) {
            throw new IllegalArgumentException("Encoding queue name must be 1-251 safe characters and not reserved");
        }
        return name;
    }

    public static Declarables declarables(String name) {
        String queue = validateQueueName(name);
        Queue source = QueueBuilder.durable(queue).deadLetterExchange(queue + ".dlx")
                .deadLetterRoutingKey(queue + ".dlq").build();
        DirectExchange dlx = ExchangeBuilder.directExchange(queue + ".dlx").durable(true).build();
        Queue dlq = QueueBuilder.durable(queue + ".dlq").build();
        return new Declarables(source, dlx, dlq, BindingBuilder.bind(dlq).to(dlx).with(queue + ".dlq"));
    }
}
