package com.vodplatform.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class CorsPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsCommaSeparatedOriginsAndNormalizesCanonicalForm() {
        contextRunner.withPropertyValues(
                        "security.cors.allowed-origins="
                                + "HTTP://LOCALHOST:80,"
                                + "https://EXAMPLE.com:443,"
                                + "https://api.example.com:8443"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CorsProperties.class);
                    assertThat(context.getBean(CorsProperties.class).allowedOrigins())
                            .containsExactly(
                                    "http://localhost",
                                    "https://example.com",
                                    "https://api.example.com:8443"
                            );
                });
    }

    @Test
    void missingOrSingleBlankValueProducesAnEmptyImmutableAllowlist() {
        CorsProperties missing = new CorsProperties(null);
        CorsProperties blank = new CorsProperties(List.of("   "));

        assertThat(missing.allowedOrigins()).isEmpty();
        assertThat(blank.allowedOrigins()).isEmpty();
        assertThatThrownBy(() -> missing.allowedOrigins().add("https://example.com"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "*",
            "ftp://example.com",
            "https://user:password@example.com",
            "https://example.com/path",
            "https://example.com?query=value",
            "https://example.com#fragment",
            "https://example.com:0",
            "https://example.com:70000",
            "https://exa mple.com"
    })
    void rejectsUnsafeOrInvalidOrigin(String origin) {
        assertThatThrownBy(() -> new CorsProperties(List.of(origin)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankEntriesInsideConfiguredList() {
        assertThatThrownBy(() -> new CorsProperties(List.of(
                "https://one.example.com",
                " ",
                "https://two.example.com"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsNullEntriesWithAConfigurationError() {
        assertThatThrownBy(() -> new CorsProperties(Collections.singletonList(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsDuplicatesAfterCanonicalNormalization() {
        assertThatThrownBy(() -> new CorsProperties(List.of(
                "HTTPS://EXAMPLE.com:443",
                "https://example.com"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicates");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CorsProperties.class)
    static class TestConfiguration {
    }
}
