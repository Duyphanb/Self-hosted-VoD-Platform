package com.vodplatform.auth.dto;

import java.util.List;
import java.util.UUID;

public record UserProfile(
        UUID id,
        String email,
        String displayName,
        List<String> roles
) {

    public UserProfile {
        roles = List.copyOf(roles);
    }
}
