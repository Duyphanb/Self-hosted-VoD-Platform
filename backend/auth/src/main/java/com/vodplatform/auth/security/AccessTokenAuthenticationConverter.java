package com.vodplatform.auth.security;

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

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            String subject = requiredClaim(jwt.getSubject());
            String userIdClaim = requiredClaim(jwt.getClaimAsString("userId"));
            UUID userId = UUID.fromString(userIdClaim);
            if (!UUID.fromString(subject).equals(userId)) {
                throw invalidToken();
            }

            String email = requiredClaim(jwt.getClaimAsString("email"));
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null || roles.isEmpty() || roles.stream().anyMatch(role -> !ALLOWED_ROLES.contains(role))) {
                throw invalidToken();
            }
            List<String> normalizedRoles = roles.stream()
                    .distinct()
                    .sorted()
                    .toList();
            List<SimpleGrantedAuthority> authorities = normalizedRoles.stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();
            AuthenticatedUserPrincipal principal = new AuthenticatedUserPrincipal(
                    userId,
                    email,
                    normalizedRoles
            );

            return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        } catch (InvalidBearerTokenException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidBearerTokenException("Invalid access token", exception);
        }
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
