package com.vodplatform.user.dto;

import com.vodplatform.shared.validation.ValidDisplayName;

public record UpdateProfileRequest(
        @ValidDisplayName String displayName
) {
}
