package com.vodplatform.encoding.messaging;

import com.vodplatform.encoding.config.EncodingQueueProperties;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Explicit JSON bytes plus per-send confirms and mandatory returns; no template-level retry. */
public final class RabbitEncodingJobPublisher implements EncodingJobPublisher {
    private static final Logger log = LoggerFactory.getLogger(RabbitEncodingJobPublisher.class);
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private final RabbitTemplate template;
    private final EncodingJobMessageCodec codec;
    private final EncodingQueueProperties properties;

    public RabbitEncodingJobPublisher(RabbitTemplate template, EncodingJobMessageCodec codec,
            EncodingQueueProperties properties) {
        this.template = Objects.requireNonNull(template, "template");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public Duration confirmTimeout() { return Duration.ofMillis(properties.publishConfirmTimeoutMs()); }

    @Override
    public PublishOutcome publish(EncodingJobMessage message, Optional<String> requestId) {
        Objects.requireNonNull(message, "message");
        String safeRequestId = Objects.requireNonNull(requestId, "requestId")
                .filter(value -> REQUEST_ID.matcher(value).matches()).orElse(null);
        PublishOutcome outcome;
        String exceptionClass = "none";
        try {
            MessageProperties headers = new MessageProperties();
            headers.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            headers.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            headers.setMessageId(message.encodingJobId().toString());
            if (safeRequestId != null) headers.setHeader("x-request-id", safeRequestId);
            // Each attempt, including a republish of the same job, must have its own correlation.
            CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
            template.send("", properties.name(), new Message(codec.encode(message), headers), correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(properties.publishConfirmTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                outcome = new PublishOutcome.Nacked(confirm.reason());
            } else if (correlation.getReturned() != null) {
                // Spring stores the return before completing the confirm future.
                var returned = correlation.getReturned();
                outcome = new PublishOutcome.Returned(returned.getReplyCode(), returned.getReplyText());
            } else {
                outcome = new PublishOutcome.Confirmed();
            }
        } catch (TimeoutException ignored) {
            outcome = new PublishOutcome.TimedOut();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            outcome = new PublishOutcome.Failed(PublishOutcome.Kind.INTERRUPTED);
        } catch (AmqpConnectException failure) {
            exceptionClass = failure.getClass().getSimpleName();
            outcome = new PublishOutcome.Failed(PublishOutcome.Kind.UNAVAILABLE);
        } catch (AmqpException | ExecutionException failure) {
            exceptionClass = failure.getClass().getSimpleName();
            outcome = new PublishOutcome.Failed(PublishOutcome.Kind.BROKER_ERROR);
        } catch (RuntimeException failure) {
            exceptionClass = failure.getClass().getSimpleName();
            outcome = new PublishOutcome.Failed(PublishOutcome.Kind.UNEXPECTED);
        }
        logOutcome(message, safeRequestId, outcome, exceptionClass);
        return outcome;
    }

    private static void logOutcome(EncodingJobMessage message, String requestId, PublishOutcome outcome,
            String exceptionClass) {
        String type = outcome.getClass().getSimpleName();
        String kind = outcome instanceof PublishOutcome.Failed failed ? failed.kind().name() : "none";
        int replyCode = outcome instanceof PublishOutcome.Returned returned ? returned.replyCode() : 0;
        // Broker reason/text and exception messages can contain untrusted or secret-bearing data.
        String format = "encoding publication outcome={} kind={} replyCode={} exceptionClass={} encodingJobId={} videoAssetId={} attempt={} requestId={}; publication unconfirmed; relay handles eligible QUEUED jobs";
        Object[] values = {type, kind, replyCode, exceptionClass, message.encodingJobId(),
                message.videoAssetId(), message.attempt(), requestId};
        if (outcome instanceof PublishOutcome.Confirmed) {
            log.info("encoding job published encodingJobId={} videoAssetId={} attempt={} requestId={}",
                    message.encodingJobId(), message.videoAssetId(), message.attempt(), requestId);
        } else if (outcome instanceof PublishOutcome.Failed failed && failed.kind() == PublishOutcome.Kind.UNEXPECTED) {
            log.error(format, values);
        } else {
            log.warn(format, values);
        }
    }
}
