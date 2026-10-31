package com.vodplatform.worker.queue.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

/** The same ADR-010 assertions run in both reactors; no runtime topology bean is added here. */
class EncodingQueueTopologyTests {
    @ParameterizedTest
    @ValueSource(strings = {"video-encoding-queue", "encoding.custom_170"})
    void declarationsMatchTheFrozenClassicTopology(String name) {
        var declarations = EncodingQueueTopology.declarables(name);
        assertThat(declarations.getDeclarables()).hasSize(4);
        var queues = declarations.getDeclarablesByType(Queue.class);
        assertThat(queues).hasSize(2);
        Queue source = queues.stream().filter(q -> q.getName().equals(name)).findFirst().orElseThrow();
        Queue dlq = queues.stream().filter(q -> q.getName().equals(name + ".dlq")).findFirst().orElseThrow();
        for (Queue queue : queues) {
            assertThat(queue.isDurable()).isTrue();
            assertThat(queue.isExclusive()).isFalse();
            assertThat(queue.isAutoDelete()).isFalse();
        }
        assertThat(source.getArguments()).isEqualTo(Map.of("x-dead-letter-exchange", name + ".dlx",
                "x-dead-letter-routing-key", name + ".dlq"));
        assertThat(dlq.getArguments()).isEmpty();
        var exchanges = declarations.getDeclarablesByType(DirectExchange.class);
        assertThat(exchanges).hasSize(1);
        DirectExchange dlx = exchanges.getFirst();
        assertThat(dlx.getName()).isEqualTo(name + ".dlx");
        assertThat(dlx.getType()).isEqualTo("direct");
        assertThat(dlx.isDurable()).isTrue();
        assertThat(dlx.isAutoDelete()).isFalse();
        assertThat(dlx.isInternal()).isFalse();
        assertThat(dlx.getArguments()).isEmpty();
        var bindings = declarations.getDeclarablesByType(Binding.class);
        assertThat(bindings).hasSize(1);
        Binding binding = bindings.getFirst();
        assertThat(binding.getDestination()).isEqualTo(name + ".dlq");
        assertThat(binding.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
        assertThat(binding.getExchange()).isEqualTo(name + ".dlx");
        assertThat(binding.getRoutingKey()).isEqualTo(name + ".dlq");
        assertThat(binding.getArguments()).isEmpty();
    }

    @Test
    void rejectsUnsafeReservedOrOversizedNamesBeforeDeclaration() {
        for (String name : new String[] {null, "", "amq.reserved", "with space", "with/slash", "x".repeat(252)}) {
            assertThatThrownBy(() -> EncodingQueueTopology.declarables(name)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(EncodingQueueTopology.validateQueueName("x".repeat(251))).hasSize(251);
    }
}
