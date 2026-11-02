package com.vodplatform.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The password bound matches registration, so every registrable password still signs in, and an oversized
 * value is rejected before any lookup or hashing.
 */
public record LoginRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(max = 72) String password
) {
}
