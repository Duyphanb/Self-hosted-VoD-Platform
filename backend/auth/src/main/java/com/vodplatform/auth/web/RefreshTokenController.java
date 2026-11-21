package com.vodplatform.auth.web;

import com.vodplatform.auth.dto.AuthResponse;
import com.vodplatform.auth.service.RefreshTokenRotationService;
import jakarta.persistence.PersistenceException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class RefreshTokenController {

    private final RefreshTokenRotationService refreshTokenRotationService;
    private final RefreshCookie cookie;
    private final com.vodplatform.shared.error.ApiErrorFactory errors;

    public RefreshTokenController(RefreshTokenRotationService refreshTokenRotationService,
                                  RefreshCookie cookie, com.vodplatform.shared.error.ApiErrorFactory errors) {
        this.refreshTokenRotationService = refreshTokenRotationService;
        this.cookie = cookie;
        this.errors = errors;
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(jakarta.servlet.http.HttpServletRequest request) {
        try {
            String token = cookie.read(request);
            if (token == null) throw new com.vodplatform.auth.exception.InvalidRefreshTokenException();
            var issued = refreshTokenRotationService.rotate(token);
            return ResponseEntity.ok().header("Set-Cookie", cookie.issued(issued.refreshToken(), issued.refreshExpiresAt()))
                    .body(AuthResponse.of(issued));
        } catch (com.vodplatform.auth.exception.InvalidRefreshTokenException invalid) {
            return ResponseEntity.status(401).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .header("Set-Cookie", cookie.cleared()).body(errors.create(
                            org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                            "Invalid refresh token", java.util.List.of()));
        } catch (DataAccessException | TransactionException | PersistenceException unavailable) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errors.create(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_STATE_UNAVAILABLE",
                            "Authentication state is unavailable", List.of()));
        }
    }
}
