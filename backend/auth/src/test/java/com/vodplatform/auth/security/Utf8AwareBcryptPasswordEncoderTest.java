package com.vodplatform.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Utf8AwareBcryptPasswordEncoderTest {

    private final Utf8AwareBcryptPasswordEncoder passwordEncoder = new Utf8AwareBcryptPasswordEncoder();

    @Test
    void suffixBeyondBcryptByteBoundaryCannotAuthenticate() {
        String password = "a".repeat(72);
        String encodedPassword = passwordEncoder.encode(password);

        assertThat(passwordEncoder.matches(password, encodedPassword)).isTrue();
        assertThat(passwordEncoder.matches(password + "suffix", encodedPassword)).isFalse();
    }

    @Test
    void supportsFullUnicodeCharacterLimitWithoutBcryptTruncation() {
        String password = "密".repeat(72);
        String encodedPassword = passwordEncoder.encode(password);

        assertThat(password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(72);
        assertThat(passwordEncoder.matches(password, encodedPassword)).isTrue();
        assertThat(passwordEncoder.matches(password + "異", encodedPassword)).isFalse();
    }
}
