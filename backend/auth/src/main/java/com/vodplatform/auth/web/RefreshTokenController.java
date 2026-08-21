package com.vodplatform.auth.web;

import com.vodplatform.auth.dto.AuthResponse;
import com.vodplatform.auth.dto.RefreshRequest;
import com.vodplatform.auth.service.RefreshTokenRotationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class RefreshTokenController {

    private final RefreshTokenRotationService refreshTokenRotationService;

    public RefreshTokenController(RefreshTokenRotationService refreshTokenRotationService) {
        this.refreshTokenRotationService = refreshTokenRotationService;
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(refreshTokenRotationService.rotate(request));
    }
}
