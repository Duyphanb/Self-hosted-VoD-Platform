package com.vodplatform.user.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UpdateProfileRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void acceptsDisplayNamesWithinFrozenBounds() {
        assertThat(validator.validate(new UpdateProfileRequest("AB"))).isEmpty();
        assertThat(validator.validate(new UpdateProfileRequest("A".repeat(100)))).isEmpty();
    }

    @Test
    void rejectsMissingShortAndLongDisplayNames() {
        assertThat(validator.validate(new UpdateProfileRequest(null))).isNotEmpty();
        assertThat(validator.validate(new UpdateProfileRequest(""))).isNotEmpty();
        assertThat(validator.validate(new UpdateProfileRequest("A"))).isNotEmpty();
        assertThat(validator.validate(new UpdateProfileRequest("A".repeat(101)))).isNotEmpty();
    }
}
