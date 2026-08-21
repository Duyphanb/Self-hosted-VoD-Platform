package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.vodplatform.auth.service.RegistrationService;
import com.vodplatform.auth.web.RegistrationController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuthComponentScanTests {

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private RegistrationController registrationController;

    @Test
    void executableApplicationLoadsAuthComponentsFromRuntimeClasspath() {
        assertThat(registrationService).isNotNull();
        assertThat(registrationController).isNotNull();
    }
}
