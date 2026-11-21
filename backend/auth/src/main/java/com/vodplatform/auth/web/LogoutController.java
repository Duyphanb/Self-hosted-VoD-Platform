package com.vodplatform.auth.web;

import com.vodplatform.auth.service.LogoutService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class LogoutController {

    private final LogoutService logoutService;
    private final RefreshCookie cookie;

    public LogoutController(LogoutService logoutService, RefreshCookie cookie) {
        this.logoutService = logoutService;
        this.cookie = cookie;
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            jakarta.servlet.http.HttpServletRequest request
    ) {
        String token = cookie.read(request);
        if (token != null) logoutService.logout(token);
        return ResponseEntity.noContent().header("Set-Cookie", cookie.cleared()).build();
    }
}
