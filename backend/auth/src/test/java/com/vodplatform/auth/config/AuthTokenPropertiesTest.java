package com.vodplatform.auth.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AuthTokenPropertiesTest {

    private static final String SECRET = "test-only-secret-with-at-least-32-bytes";

    @ParameterizedTest
    @CsvSource({"1,1", "15,30"})
    void acceptsInclusiveDurationBounds(int minutes, int days) {
        assertThatCode(() -> new AuthTokenProperties(SECRET, Duration.ofMinutes(minutes), Duration.ofDays(days)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"0,7", "16,7", "60,7", "5,0", "5,31"})
    void rejectsDurationsOutsideApprovedBounds(int minutes, int days) {
        assertThatThrownBy(() -> new AuthTokenProperties(SECRET, Duration.ofMinutes(minutes), Duration.ofDays(days)))
                .isInstanceOf(IllegalArgumentException.class);
    }

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
                .hasMessageContaining("Session TTL");
    }
}
