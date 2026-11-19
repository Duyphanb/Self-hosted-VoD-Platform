package com.vodplatform.auth.dto;

public record AuthResponse(
        UserProfile user,
        String accessToken,
        long expiresInSeconds
) {
    public static AuthResponse of(com.vodplatform.auth.service.IssuedAuthentication issued) {
        return new AuthResponse(issued.user(), issued.accessToken(), issued.expiresInSeconds());
    }
}
