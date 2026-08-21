package com.vodplatform.auth.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AuthTokenPropertiesTest {

    @Test
    void rejectsSecretThatIsTooShortForHs256() {
        assertThatThrownBy(() -> new AuthTokenProperties(
                "too-short",
                Duration.ofMinutes(15),
                Duration.ofDays(7)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32");
    }

    @Test
    void rejectsNonPositiveTokenTtls() {
        assertThatThrownBy(() -> new AuthTokenProperties(
                "test-only-secret-with-at-least-32-bytes",
                Duration.ZERO,
                Duration.ofDays(7)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Access token TTL");

        assertThatThrownBy(() -> new AuthTokenProperties(
                "test-only-secret-with-at-least-32-bytes",
                Duration.ofMinutes(15),
                Duration.ofSeconds(-1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Refresh token TTL");
    }
}
