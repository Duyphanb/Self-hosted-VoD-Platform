package com.vodplatform.worker.queue.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class EncodingJobListenerTests {
    private static final UUID JOB = UUID.fromString("6f1d2a4e-8c3b-4a5f-9e7d-1b2c3d4e5f60");
    private static final UUID ASSET = UUID.fromString("0b6c1f7e-2a3d-4e5f-8a9b-0c1d2e3f4a5b");
    private final List<EncodingJobMessage> processed = new ArrayList<>();
    private final EncodingJobListener listener = new EncodingJobListener(processed::add);

    @Test
    void delegatesOnlyTheGoldenIdsEvenWithAnUntrustedTypeHeader() throws Exception {
        listener.onMessage(message(QueueFixtures.golden(), JOB.toString()));

        assertThat(processed).containsExactly(new EncodingJobMessage(JOB, ASSET, 1));
    }

    @Test
    void poisonNeverReachesTheProcessorOrAppearsInTheException() throws Exception {
        assertThatThrownBy(() -> listener.onMessage(message("untrusted payload".getBytes(StandardCharsets.UTF_8),
                "untrusted id")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessage("Invalid encoding job message: MALFORMED").hasNoCause();
        byte[] golden = QueueFixtures.golden();
        assertThatThrownBy(() -> listener.onMessage(message(golden, "untrusted id")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessage("Invalid encoding job message: MESSAGE_ID_MISMATCH").hasNoCause();
        assertThat(processed).isEmpty();
    }

    private static Message message(byte[] body, String id) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(id);
        properties.setHeader("__TypeId__", "untrusted.Type");
        return new Message(body, properties);
    }
}
