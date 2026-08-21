package com.vodplatform.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RegisterRequestValidationTest {

    private static jakarta.validation.ValidatorFactory validatorFactory;
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
    void acceptsRequestMatchingFrozenContract() {
        RegisterRequest request = new RegisterRequest(
                "viewer@example.com",
                "strong-password",
                "Viewer"
        );

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsInvalidEmailWeakPasswordAndShortDisplayName() {
        RegisterRequest request = new RegisterRequest("invalid", "short", "x");

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("email", "password", "displayName");
    }

    @Test
    void rejectsFieldsLongerThanContractMaximums() {
        RegisterRequest request = new RegisterRequest(
                "a".repeat(309) + "@example.com",
                "p".repeat(73),
                "d".repeat(101)
        );

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("email", "password", "displayName");
    }

    @Test
    void acceptsExactLengthBoundaries() {
        String maximumLengthEmail = "a".repeat(64) + "@"
                + String.join(".", "b".repeat(63), "c".repeat(63), "d".repeat(63), "e".repeat(63));
        RegisterRequest minimums = new RegisterRequest("a@b.co", "p".repeat(8), "ab");
        RegisterRequest maximums = new RegisterRequest(
                maximumLengthEmail,
                "p".repeat(72),
                "d".repeat(100)
        );

        assertThat(maximumLengthEmail).hasSize(320);
        assertThat(validator.validate(minimums)).isEmpty();
        assertThat(validator.validate(maximums)).isEmpty();
    }

    @Test
    void rejectsMissingRequiredFields() {
        RegisterRequest request = new RegisterRequest(null, null, null);

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("email", "password", "displayName");
    }
}
