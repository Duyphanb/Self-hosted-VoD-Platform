package com.vodplatform.auth.security;

import java.util.Map;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Writes versioned SHA-256-prehashed BCrypt values while retaining support for
 * the unprefixed direct BCrypt hashes produced before login was implemented.
 */
public final class VersionedBcryptPasswordEncoder implements PasswordEncoder {

    static final String CURRENT_ENCODING_ID = "bcrypt-sha256";

    private final DelegatingPasswordEncoder delegate;

    public VersionedBcryptPasswordEncoder() {
        BCryptPasswordEncoder legacyBcrypt = new BCryptPasswordEncoder();
        this.delegate = new DelegatingPasswordEncoder(
                CURRENT_ENCODING_ID,
                Map.of(
                        "bcrypt", legacyBcrypt,
                        CURRENT_ENCODING_ID, new Utf8AwareBcryptPasswordEncoder()
                )
        );
        this.delegate.setDefaultPasswordEncoderForMatches(legacyBcrypt);
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return delegate.matches(rawPassword, encodedPassword);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }
}
