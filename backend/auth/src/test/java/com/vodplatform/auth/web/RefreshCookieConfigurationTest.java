package com.vodplatform.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import com.vodplatform.auth.config.AuthCookieProperties;
import com.vodplatform.auth.config.CorsProperties;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class RefreshCookieConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(Config.class);
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties({AuthCookieProperties.class,CorsProperties.class})
    static class Config {
        @Bean RefreshCookie cookie(AuthCookieProperties properties,CorsProperties cors) {
            return new RefreshCookie(properties,cors,Clock.systemUTC());
        }
    }
    @Test void defaultsToSecureEvenWithoutOrigins() {
        runner.run(context->{ assertThat(context).hasNotFailed();
            assertThat(context.getBean(AuthCookieProperties.class).secure()).isTrue(); });
    }
    @ParameterizedTest @ValueSource(strings={"http://localhost","http://127.0.0.2:8088","http://[::1]:8088",
            "http://localhost,http://127.0.0.1,http://[::1]"})
    void explicitInsecureSettingIsAcceptedOnlyForLoopbackOrigins(String origins) {
        runner.withPropertyValues("auth.cookie.secure=false","security.cors.allowed-origins="+origins)
                .run(context->assertThat(context).hasNotFailed());
    }
    @ParameterizedTest @ValueSource(strings={"","http://192.168.1.20","http://localhost.evil.example.com",
            "http://localhost,http://example.com","http://127.999.0.1","http://[fc00::1]"})
    void insecureNonLoopbackOrEmptyOriginsFailStartup(String origins) {
        runner.withPropertyValues("auth.cookie.secure=false","security.cors.allowed-origins="+origins)
                .run(context->assertThat(context).hasFailed());
    }
}
