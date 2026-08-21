package com.vodplatform.auth.web;

import com.vodplatform.auth.dto.RegisterRequest;
import com.vodplatform.auth.dto.UserProfile;
import com.vodplatform.auth.service.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    public ResponseEntity<UserProfile> register(@Valid @RequestBody RegisterRequest request) {
        UserProfile profile = registrationService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(profile);
    }
}
