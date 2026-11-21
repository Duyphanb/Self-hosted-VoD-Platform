package com.vodplatform.auth.service;

import com.vodplatform.auth.dto.UserProfile;
import java.time.Instant;

/** Internal issuance result. Only the access token is mapped into a response body. */
public record IssuedAuthentication(UserProfile user, String accessToken, String refreshToken,
                                   long expiresInSeconds, Instant refreshExpiresAt) {
    @Override public String toString() { return "IssuedAuthentication[redacted]"; }
}
