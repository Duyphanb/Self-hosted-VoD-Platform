package com.vodplatform.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class VersionedBcryptPasswordEncoderTest {

    private final VersionedBcryptPasswordEncoder passwordEncoder =
            new VersionedBcryptPasswordEncoder();

    @Test
    void authenticatesLegacyDirectBcryptHashesAndMarksThemForUpgrade() {
        String password = "legacy-password";
        String legacyHash = new BCryptPasswordEncoder().encode(password);

        assertThat(passwordEncoder.matches(password, legacyHash)).isTrue();
        assertThat(passwordEncoder.matches("wrong-password", legacyHash)).isFalse();
        assertThat(passwordEncoder.upgradeEncoding(legacyHash)).isTrue();
    }

    @Test
    void createsVersionedBcryptHashesThatDoNotNeedUpgrade() {
        String encodedPassword = passwordEncoder.encode("new-password");

        assertThat(encodedPassword).startsWith("{bcrypt-sha256}$2");
        assertThat(passwordEncoder.matches("new-password", encodedPassword)).isTrue();
        assertThat(passwordEncoder.matches("wrong-password", encodedPassword)).isFalse();
        assertThat(passwordEncoder.upgradeEncoding(encodedPassword)).isFalse();
    }

    @Test
    void preservesAllUtf8PasswordBytesForNewHashes() {
        String password = "密".repeat(72);
        String encodedPassword = passwordEncoder.encode(password);

        assertThat(passwordEncoder.matches(password, encodedPassword)).isTrue();
        assertThat(passwordEncoder.matches(password + "異", encodedPassword)).isFalse();
    }
}
