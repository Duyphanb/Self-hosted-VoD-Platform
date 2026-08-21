package com.vodplatform.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @NotNull @Size(min = 2, max = 100) String displayName
) {
}
