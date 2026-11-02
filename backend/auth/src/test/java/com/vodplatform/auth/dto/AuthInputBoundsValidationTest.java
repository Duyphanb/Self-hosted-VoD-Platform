package com.vodplatform.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AuthInputBoundsValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void loginAcceptsEveryPasswordThatRegistrationAccepts() {
        for (String password : new String[]{"a".repeat(8), "a".repeat(72), "密".repeat(72), "😀".repeat(36)}) {
            assertThat(validator.validate(new RegisterRequest("a@b.co", password, "Viewer"))).isEmpty();
            assertThat(validator.validate(new LoginRequest("a@b.co", password))).isEmpty();
        }
        assertThat(validator.validate(new LoginRequest("a@b.co", "x"))).isEmpty();
    }

    @Test
    void loginRejectsPasswordsLongerThanTheRegistrationMaximum() {
        for (String password : new String[]{"a".repeat(73), "密".repeat(73), "😀".repeat(37), "a".repeat(1_000_000)}) {
            assertThat(validator.validate(new LoginRequest("a@b.co", password)))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("password");
            assertThat(validator.validate(new RegisterRequest("a@b.co", password, "Viewer")))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("password");
        }
    }

    @Test
    void refreshTokenAcceptsIssuedLengthUpToTheBoundAndRejectsLonger() {
        assertThat(validator.validate(new RefreshRequest("A".repeat(43)))).isEmpty();
        assertThat(validator.validate(new RefreshRequest("A".repeat(128)))).isEmpty();
        for (String token : new String[]{"A".repeat(129), "A".repeat(1_000_000)}) {
            assertThat(validator.validate(new RefreshRequest(token)))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("refreshToken");
        }
    }
}
