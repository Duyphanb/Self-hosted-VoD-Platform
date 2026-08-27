package com.vodplatform.auth.service;

import com.vodplatform.auth.config.AuthTokenProperties;
import com.vodplatform.auth.persistence.RoleEntity;
import com.vodplatform.auth.persistence.UserEntity;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtAccessTokenService {

    private final JwtEncoder jwtEncoder;
    private final AuthTokenProperties properties;
    private final Clock clock;

    public JwtAccessTokenService(JwtEncoder jwtEncoder, AuthTokenProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedAccessToken issue(UserEntity user) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        List<String> roles = user.getRoles().stream()
                .map(RoleEntity::getName)
                .sorted(Comparator.naturalOrder())
                .toList();
        String userId = user.getId().toString();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("userId", userId)
                .claim("email", user.getEmail())
                .claim("roles", roles)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256)
                .type("JWT")
                .build();
        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new IssuedAccessToken(value, properties.accessTokenTtl().toSeconds());
    }
}
