package com.vodplatform.encoding.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vodplatform.encoding.config.EncodingQueueProperties;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitEncodingJobPublisherTest {
    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final EncodingJobMessageCodec codec = new EncodingJobMessageCodec();
    private final EncodingJobMessage job = new EncodingJobMessage(UUID.randomUUID(), UUID.randomUUID(), 1);
    private final RabbitEncodingJobPublisher publisher = new RabbitEncodingJobPublisher(template, codec,
            new EncodingQueueProperties("test-queue", 20));

    @Test
    void confirmedPublicationHasOnlyExplicitWirePropertiesAndFreshCorrelationPerAttempt() {
        List<CorrelationData> correlations = new ArrayList<>();
        List<Message> messages = new ArrayList<>();
        doAnswer(call -> {
            Message message = call.getArgument(2);
            CorrelationData correlation = call.getArgument(3);
            messages.add(message);
            correlations.add(correlation);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(eq(""), eq("test-queue"), any(Message.class), any(CorrelationData.class));

        assertThat(publisher.publish(job, Optional.of("safe-id_123"))).isEqualTo(new PublishOutcome.Confirmed());
        assertThat(publisher.publish(job, Optional.empty())).isEqualTo(new PublishOutcome.Confirmed());
        assertThat(correlations).hasSize(2);
        assertThat(correlations.get(0).getId()).isNotEqualTo(correlations.get(1).getId())
                .isNotEqualTo(job.encodingJobId().toString());
        for (Message message : messages) {
            assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
            assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo(job.encodingJobId().toString());
            assertThat(codec.decode(message.getBody(), message.getMessageProperties().getMessageId())).isEqualTo(job);
            assertThat(message.getMessageProperties().getHeaders()).doesNotContainKeys("__TypeId__", "__ContentTypeId__");
        }
        assertThat(messages.get(0).getMessageProperties().getHeaders()).containsOnlyKeys("x-request-id");
        assertThat(messages.get(0).getMessageProperties().getHeader("x-request-id").toString()).isEqualTo("safe-id_123");
        assertThat(messages.get(1).getMessageProperties().getHeaders()).isEmpty();
        assertThat(publisher.confirmTimeout().toMillis()).isEqualTo(20);
    }

    @Test
    void returnedAckIsNeverConfirmedAndUntrustedBrokerTextNeverReachesLogs() {
        complete(correlation -> {
            correlation.setReturned(new ReturnedMessage(new Message(new byte[0]), 312,
                    "broker-secret\nforged-log", "", "test-queue"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
        });
        Logger logger = (Logger) LoggerFactory.getLogger(RabbitEncodingJobPublisher.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        logger.addAppender(capture);
        try {
            assertThat(publisher.publish(job, Optional.of("request\ninjected")))
                    .isEqualTo(new PublishOutcome.Returned(312, "broker-secret\nforged-log"));
            assertThat(capture.list).hasSize(1);
            assertThat(capture.list.getFirst().getFormattedMessage()).contains("outcome=Returned", "replyCode=312",
                            job.encodingJobId().toString(), "publication unconfirmed; relay handles eligible QUEUED jobs")
                    .doesNotContain("broker-secret", "forged-log", "request\ninjected");
            assertThat(capture.list.getFirst().getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(capture);
            capture.stop();
        }
    }

    @Test
    void brokerAndFrameworkNacksAreDistinctFromSuccess() {
        for (String reason : new String[]{null, "channel closed"}) {
            complete(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(false, reason)));
            assertThat(publisher.publish(job, Optional.empty())).isEqualTo(new PublishOutcome.Nacked(reason));
        }
    }

    @Test
    void timeoutIgnoresLateAck() {
        List<CorrelationData> correlations = new ArrayList<>();
        complete(correlations::add);
        PublishOutcome result = publisher.publish(job, Optional.empty());
        assertThat(result).isEqualTo(new PublishOutcome.TimedOut());
        correlations.getFirst().getFuture().complete(new CorrelationData.Confirm(true, null));
        assertThat(result).isEqualTo(new PublishOutcome.TimedOut());
    }

    @Test
    void interruptedWaitRestoresTheThreadFlag() {
        try {
            Thread.currentThread().interrupt();
            assertThat(publisher.publish(job, Optional.empty()))
                    .isEqualTo(new PublishOutcome.Failed(PublishOutcome.Kind.INTERRUPTED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void expectedFailuresAndUnexpectedRuntimeExceptionReturnOutcomesRatherThanThrow() {
        fail(new AmqpConnectException(new ConnectException("withheld")));
        assertThat(publisher.publish(job, Optional.empty()))
                .isEqualTo(new PublishOutcome.Failed(PublishOutcome.Kind.UNAVAILABLE));
        fail(new AmqpException("withheld"));
        assertThat(publisher.publish(job, Optional.empty()))
                .isEqualTo(new PublishOutcome.Failed(PublishOutcome.Kind.BROKER_ERROR));
        fail(new IllegalStateException("withheld"));
        assertThat(publisher.publish(job, Optional.empty()))
                .isEqualTo(new PublishOutcome.Failed(PublishOutcome.Kind.UNEXPECTED));
    }

    @Test
    void failedFutureReturnsBrokerError() {
        complete(correlation -> correlation.getFuture().completeExceptionally(new AmqpException("withheld")));
        assertThat(publisher.publish(job, Optional.empty()))
                .isEqualTo(new PublishOutcome.Failed(PublishOutcome.Kind.BROKER_ERROR));
    }

    @Test
    void fatalErrorsStillPropagate() {
        doThrow(new AssertionError("fatal")).when(template).send(eq(""), eq("test-queue"), any(Message.class), any(CorrelationData.class));
        assertThatThrownBy(() -> publisher.publish(job, Optional.empty())).isInstanceOf(AssertionError.class);
    }

    private void complete(Consumer<CorrelationData> answer) {
        doAnswer(call -> { answer.accept(call.getArgument(3)); return null; })
                .when(template).send(eq(""), eq("test-queue"), any(Message.class), any(CorrelationData.class));
    }

    private void fail(RuntimeException failure) {
        doThrow(failure).when(template).send(eq(""), eq("test-queue"), any(Message.class), any(CorrelationData.class));
    }
}
