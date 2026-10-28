package com.vodplatform.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RegisterRequestValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {"  ", "\u00a0\u2007\u202f", "\u0000Viewer", "Viewer\u0000", "View\rer", "View\ner", "\tViewer", "View\u007fer", "View\u009fer", " A "})
    void rejectsBlankControlAndTrimmedShortNames(String name) {
        assertThat(validator.validate(new RegisterRequest("a@b.co", "password", name)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("displayName");
    }

    @Test
    void usesRawCodePointBoundsAndPreservesFormatCharactersLikeCatalog() {
        for (String name : new String[]{" AB ", "\u00a0" + "A".repeat(98) + "\u202f", "😀".repeat(2), "😀".repeat(51), "😀".repeat(100), "A\u200b\u202e\ufeffB", "Tên  Việt"}) {
            assertThat(validator.validate(new RegisterRequest("a@b.co", "password", name))).isEmpty();
        }
        for (int codePoint = 0; codePoint <= 0x9f; codePoint++) {
            if (Character.isISOControl(codePoint)) {
                assertThat(validator.validate(new RegisterRequest("a@b.co", "password", "A" + (char) codePoint + "B")))
                        .as("Cc U+%04X", codePoint).isNotEmpty();
            }
        }
    }

    @Test
    void rejectsRawOverflowAndSingleCodePointAfterTrimming() {
        for (String name : new String[]{"😀", " 😀 ", "😀".repeat(101), " " + "A".repeat(100) + " ", " ".repeat(10000) + "AB"}) {
            assertThat(validator.validate(new RegisterRequest("a@b.co", "password", name)))
                    .extracting(violation -> violation.getMessage()).containsExactly("size must be between 2 and 100");
        }
    }

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
