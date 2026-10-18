package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "auth.tokens.secret=test-only-secret-with-at-least-32-bytes"
)
class AppClockContextTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void applicationDefinesExactlyOneUtcClockNamedAppClock() {
        Map<String, Clock> clocks = applicationContext.getBeansOfType(Clock.class);

        assertThat(clocks).containsOnlyKeys("appClock");
        assertThat(clocks.get("appClock").getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
