package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.vodplatform.auth.service.LoginService;
import com.vodplatform.auth.service.RefreshTokenRotationService;
import com.vodplatform.auth.service.RegistrationService;
import com.vodplatform.auth.web.LoginController;
import com.vodplatform.auth.web.RefreshTokenController;
import com.vodplatform.auth.web.RegistrationController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "auth.tokens.secret=test-only-secret-with-at-least-32-bytes"
)
class AuthComponentScanTests {

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private RegistrationController registrationController;

    @Autowired
    private LoginService loginService;

    @Autowired
    private LoginController loginController;

    @Autowired
    private RefreshTokenRotationService refreshTokenRotationService;

    @Autowired
    private RefreshTokenController refreshTokenController;

    @Test
    void executableApplicationLoadsAuthComponentsFromRuntimeClasspath() {
        assertThat(registrationService).isNotNull();
        assertThat(registrationController).isNotNull();
        assertThat(loginService).isNotNull();
        assertThat(loginController).isNotNull();
        assertThat(refreshTokenRotationService).isNotNull();
        assertThat(refreshTokenController).isNotNull();
    }
}
