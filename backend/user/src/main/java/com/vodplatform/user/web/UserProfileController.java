package com.vodplatform.user.web;

import com.vodplatform.auth.dto.UserProfile;
import com.vodplatform.auth.security.AuthenticatedUserPrincipal;
import com.vodplatform.user.dto.UpdateProfileRequest;
import com.vodplatform.user.service.CurrentUserProfileService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
public class UserProfileController {

    private final CurrentUserProfileService currentUserProfileService;

    public UserProfileController(CurrentUserProfileService currentUserProfileService) {
        this.currentUserProfileService = currentUserProfileService;
    }

    @GetMapping
    public ResponseEntity<UserProfile> getCurrentUser(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal
    ) {
        return ResponseEntity.ok(currentUserProfileService.getProfile(principal.userId()));
    }

    @PutMapping
    public ResponseEntity<UserProfile> updateCurrentUser(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        return ResponseEntity.ok(currentUserProfileService.updateDisplayName(
                principal.userId(),
                request.displayName()
        ));
    }
}
