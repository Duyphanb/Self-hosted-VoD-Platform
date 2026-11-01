package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class RelayYamlConfigurationTests {

    @Test
    void applicationYamlKeepsEnvironmentDrivenTenSecondRelayDefault() throws Exception {
        var sources =
                new YamlPropertySourceLoader()
                        .load("application", new ClassPathResource("application.yml"));
        assertThat(sources).hasSize(1);
        assertThat(sources.getFirst().getProperty("encoding.relay.interval-seconds"))
                .isEqualTo("${ENCODING_RELAY_INTERVAL_SECONDS:10}");
    }
}
