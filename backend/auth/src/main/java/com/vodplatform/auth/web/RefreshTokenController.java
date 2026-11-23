package com.vodplatform.auth.web;

import com.vodplatform.auth.dto.AuthResponse;
import com.vodplatform.auth.exception.InvalidRefreshTokenException;
import com.vodplatform.auth.service.RefreshTokenRotationService;
import com.vodplatform.shared.error.ApiErrorFactory;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class RefreshTokenController {

    private final RefreshTokenRotationService refreshTokenRotationService;
    private final RefreshCookie cookie;
    private final ApiErrorFactory errors;

    public RefreshTokenController(
            RefreshTokenRotationService refreshTokenRotationService,
            RefreshCookie cookie,
            ApiErrorFactory errors
    ) {
        this.refreshTokenRotationService = refreshTokenRotationService;
        this.cookie = cookie;
        this.errors = errors;
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request) {
        try {
            String token = cookie.read(request);
            if (token == null) {
                throw new InvalidRefreshTokenException();
            }
            var issued = refreshTokenRotationService.rotate(token);
            return ResponseEntity.ok()
                    .header("Set-Cookie", cookie.issued(issued.refreshToken(), issued.refreshExpiresAt()))
                    .body(AuthResponse.of(issued));
        } catch (InvalidRefreshTokenException invalid) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Set-Cookie", cookie.cleared())
                    .body(errors.create(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid refresh token", List.of()));
        }
    }
}
