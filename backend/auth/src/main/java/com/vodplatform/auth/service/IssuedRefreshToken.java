package com.vodplatform.auth.service;

import java.time.Instant;

public record IssuedRefreshToken(String value, String hash, Instant expiresAt) {
}
