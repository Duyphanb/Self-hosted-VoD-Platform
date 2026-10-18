package com.vodplatform.shared.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Defines the single application-wide {@link Clock}. Modules inject it by type and must not
 * define another {@code Clock} bean.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {

    @Bean
    Clock appClock() {
        return Clock.systemUTC();
    }
}
