package com.vodplatform.auth.dto;

import com.vodplatform.shared.validation.ValidDisplayName;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @ValidDisplayName String displayName
) {
}
