package com.vodplatform.encoding.config;

import com.vodplatform.encoding.service.EncodingRelay;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(EncodingRelayProperties.class)
public class EncodingRelayConfiguration implements SchedulingConfigurer {

    private final EncodingRelayProperties properties;

    private final EncodingRelay relay;

    public EncodingRelayConfiguration(EncodingRelayProperties properties, EncodingRelay relay) {
        this.properties = properties;
        this.relay = relay;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        Duration interval = Duration.ofSeconds(properties.intervalSeconds());
        registrar.addFixedDelayTask(new FixedDelayTask(relay::relayOnce, interval, interval));
    }
}
