package com.vodplatform.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Issued refresh tokens are 43 characters; the 128-character bound keeps the token opaque while rejecting an
 * oversized value before any hashing or lookup.
 */
public record RefreshRequest(
        @NotBlank @Size(max = 128) String refreshToken
) {
}
