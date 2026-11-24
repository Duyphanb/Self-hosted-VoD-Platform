package com.vodplatform.auth.security;

import com.vodplatform.auth.persistence.SessionState;
import com.vodplatform.auth.service.SessionStateCache;
import com.vodplatform.auth.service.SessionStateReader;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

@Component
public class AccessTokenAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Set<String> ALLOWED_ROLES = Set.of("ROLE_USER", "ROLE_ADMIN");

    private final SessionStateCache cache;
    private final SessionStateReader reader;
    private final Clock clock;

    public AccessTokenAuthenticationConverter(SessionStateCache cache, SessionStateReader reader, Clock clock) {
        this.cache = cache;
        this.reader = reader;
        this.clock = clock;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return convert(jwt, false);
    }

    public AbstractAuthenticationToken convertAdmin(Jwt jwt) {
        return convert(jwt, true);
    }

    private AbstractAuthenticationToken convert(Jwt jwt, boolean adminRequest) {
        Claims claims = parseClaims(jwt);
        var state = adminRequest ? reader.read(claims.sessionId(), true) : cache.read(claims.sessionId());
        SessionState session = state.filter(value -> value.accepts(claims.userId(), claims.epoch(), clock.instant()))
                .orElseThrow(this::invalidToken);

        List<String> roles = claims.roles();
        if (adminRequest && session.adminRole() == null) {
            roles = roles.stream().filter(role -> !role.equals("ROLE_ADMIN")).toList();
        }
        List<SimpleGrantedAuthority> authorities = roles.stream().map(SimpleGrantedAuthority::new).toList();
        var principal = new AuthenticatedUserPrincipal(claims.userId(), claims.email(), roles);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }

    private Claims parseClaims(Jwt jwt) {
        try {
            String subject = requiredClaim(jwt.getSubject());
            String userIdClaim = requiredClaim(jwt.getClaimAsString("userId"));
            UUID userId = UUID.fromString(userIdClaim);
            if (!UUID.fromString(subject).equals(userId)) {
                throw invalidToken();
            }

            String email = requiredClaim(jwt.getClaimAsString("email"));
            UUID sessionId = UUID.fromString(requiredClaim(jwt.getClaimAsString("sid")));
            Object version = jwt.getClaim("ver");
            if (!(version instanceof Integer || version instanceof Long)) {
                throw invalidToken();
            }
            int epoch = Math.toIntExact(((Number) version).longValue());
            if (epoch < 0) {
                throw invalidToken();
            }
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null || roles.isEmpty() || roles.stream().anyMatch(role -> !ALLOWED_ROLES.contains(role))) {
                throw invalidToken();
            }
            List<String> normalizedRoles = roles.stream()
                    .distinct()
                    .sorted()
                    .toList();
            return new Claims(userId, email, normalizedRoles, sessionId, epoch);
        } catch (InvalidBearerTokenException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidBearerTokenException("Invalid access token", exception);
        }
    }

    private record Claims(UUID userId, String email, List<String> roles, UUID sessionId, int epoch) {
    }

    private String requiredClaim(String value) {
        if (value == null || value.isBlank()) {
            throw invalidToken();
        }
        return value;
    }

    private InvalidBearerTokenException invalidToken() {
        return new InvalidBearerTokenException("Invalid access token");
    }
}
