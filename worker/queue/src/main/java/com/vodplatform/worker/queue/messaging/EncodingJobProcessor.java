package com.vodplatform.worker.queue.messaging;

/**
 * Delivery boundary for the processing service. Return only after a terminal outcome is persisted
 * or after verifying a safe duplicate or stale attempt. Reject a mismatched job/asset/attempt
 * with AmqpRejectAndDontRequeueException. Database outages hold the delivery and retry in process.
 */
@FunctionalInterface
public interface EncodingJobProcessor {
    void process(EncodingJobMessage message);
}
