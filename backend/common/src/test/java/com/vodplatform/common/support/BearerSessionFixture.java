package com.vodplatform.common.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/** Supplies valid signed tokens to endpoint tests that do not exercise the login flow. */
public final class BearerSessionFixture implements BeforeEachCallback, AfterEachCallback {

    private final List<UUID> ownedUsers = new ArrayList<>();
    private JdbcTemplate jdbc;
    private JwtEncoder encoder;

    @Override
    public void beforeEach(ExtensionContext context) {
        var spring = SpringExtension.getApplicationContext(context);
        jdbc = spring.getBean(JdbcTemplate.class);
        encoder = spring.getBean(JwtEncoder.class);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        for (UUID userId : ownedUsers) {
            jdbc.update("delete from users where id = ?", userId);
        }
        ownedUsers.clear();
    }

    public String token(String role) {
        UUID userId = UUID.randomUUID();
        String email = userId + "@example.test";
        ownedUsers.add(userId);
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, status, created_at, updated_at)
                values (?, ?, 'unused', 'Bearer fixture', 'ACTIVE', now(), now())
                """, userId, email);
        jdbc.update("insert into user_roles (user_id, role_id) select ?, id from roles where name = ?", userId, role);
        return tokenFor(userId, email, role);
    }

    public String tokenFor(UUID userId, String email, String role) {
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(300);
        jdbc.update("insert into auth_sessions (id, user_id, created_at, expires_at) values (?, ?, ?, ?)",
                sessionId, userId, Timestamp.from(now), Timestamp.from(expiresAt.plusSeconds(60)));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("userId", userId.toString())
                .claim("email", email)
                .claim("roles", List.of(role))
                .claim("sid", sessionId.toString())
                .claim("ver", 0)
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
