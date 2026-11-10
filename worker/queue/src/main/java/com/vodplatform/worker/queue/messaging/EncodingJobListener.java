package com.vodplatform.worker.queue.messaging;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;

/** Consumes raw messages so no Java type header or wire-supplied storage key reaches the processor. */
final class EncodingJobListener implements MessageListener {
    private final EncodingJobMessageCodec codec = new EncodingJobMessageCodec();
    private final EncodingJobProcessor processor;

    EncodingJobListener(EncodingJobProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void onMessage(Message message) {
        EncodingJobMessage job;
        try {
            job = codec.decode(message.getBody(), message.getMessageProperties().getMessageId());
        } catch (InvalidEncodingJobMessageException invalid) {
            throw new AmqpRejectAndDontRequeueException(invalid.getMessage());
        }
        processor.process(job);
    }
}
