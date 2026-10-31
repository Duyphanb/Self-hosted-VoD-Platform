package com.vodplatform.encoding.config;

import com.vodplatform.encoding.messaging.EncodingQueueTopology;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "encoding.queue")
public record EncodingQueueProperties(String name, long publishConfirmTimeoutMs) {
    public EncodingQueueProperties {
        EncodingQueueTopology.validateQueueName(name);
        if (publishConfirmTimeoutMs < 1 || publishConfirmTimeoutMs > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Encoding confirm timeout must be positive milliseconds within integer range");
        }
    }
}
