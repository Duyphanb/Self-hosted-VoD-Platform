package com.vodplatform.encoding.messaging;

import java.time.Duration;
import java.util.Optional;

/** Broker boundary only. The caller commits first; publication has no database or storage effects. */
public interface EncodingJobPublisher {
    PublishOutcome publish(EncodingJobMessage message, Optional<String> requestId);
    /** Bounds the confirm wait; connection and channel RPC have their own configured budgets. */
    Duration confirmTimeout();
}
