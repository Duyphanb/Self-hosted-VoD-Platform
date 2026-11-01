package com.vodplatform.encoding.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("encoding.relay")
public record EncodingRelayProperties(@DefaultValue("10") long intervalSeconds) {
    public EncodingRelayProperties {
        if (intervalSeconds < 1 || intervalSeconds > 86400) {
            throw new IllegalArgumentException(
                    "Relay interval must be between 1 and 86400 seconds");
        }
    }
}
