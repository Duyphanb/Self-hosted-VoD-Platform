package com.vodplatform.auth.security;

import java.security.Principal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AuthenticatedUserPrincipal(
        UUID userId,
        String email,
        List<String> roles
) implements Principal {

    public AuthenticatedUserPrincipal {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(email, "email must not be null");
        roles = List.copyOf(Objects.requireNonNull(roles, "roles must not be null"));
    }

    @Override
    public String getName() {
        return userId.toString();
    }
}
