package com.vodplatform.auth.dto;

public record AuthResponse(
        UserProfile user,
        String accessToken,
        String refreshToken,
        long expiresInSeconds
) {
}
