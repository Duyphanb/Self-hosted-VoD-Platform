package com.vodplatform.user.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UpdateProfileRequestValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {"  ", "\u00a0\u2007\u202f", "\u0000Viewer", "Viewer\u0000", "View\rer", "View\ner", "\tViewer", "View\u007fer", "View\u009fer", " A "})
    void rejectsBlankControlAndTrimmedShortNames(String name) {
        assertThat(validator.validate(new UpdateProfileRequest(name)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("displayName");
    }

    @Test
    void usesRawCodePointBoundsAndPreservesFormatCharactersLikeCatalog() {
        for (String name : new String[]{" AB ", "\u00a0" + "A".repeat(98) + "\u202f", "😀".repeat(2), "😀".repeat(51), "😀".repeat(100), "A\u200b\u202e\ufeffB", "Tên  Việt"}) {
            assertThat(validator.validate(new UpdateProfileRequest(name))).isEmpty();
        }
        for (int codePoint = 0; codePoint <= 0x9f; codePoint++) {
            if (Character.isISOControl(codePoint)) {
                assertThat(validator.validate(new UpdateProfileRequest("A" + (char) codePoint + "B")))
                        .as("Cc U+%04X", codePoint).isNotEmpty();
            }
        }
    }

    @Test
    void rejectsRawOverflowAndSingleCodePointAfterTrimming() {
        for (String name : new String[]{"😀", " 😀 ", "😀".repeat(101), " " + "A".repeat(100) + " ", " ".repeat(10000) + "AB"}) {
            assertThat(validator.validate(new UpdateProfileRequest(name)))
                    .extracting(violation -> violation.getMessage()).containsExactly("size must be between 2 and 100");
        }
    }

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
