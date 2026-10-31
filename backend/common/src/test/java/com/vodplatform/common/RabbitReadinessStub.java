package com.vodplatform.common;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** HTTP/security tests isolate readiness; production retains Boot's real Rabbit contributor. */
@TestConfiguration(proxyBeanMethods = false)
class RabbitReadinessStub {
    @Bean
    HealthIndicator rabbitHealthContributor() {
        return () -> Health.up().build();
    }
}
