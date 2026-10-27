package com.vodplatform.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CanonicalUuidEditorTest {

    private final CanonicalUuidEditor editor = new CanonicalUuidEditor();

    @ParameterizedTest
    @ValueSource(strings = {
            "123e4567-e89b-12d3-a456-426614174000",
            "123E4567-E89B-12D3-A456-426614174000",
            "123e4567-E89B-12d3-A456-426614174000",
            "00000000-0000-0000-0000-000000000000",
            "ffffffff-ffff-ffff-ffff-ffffffffffff"
    })
    void acceptsTheCanonicalFormInEitherCase(String text) {
        editor.setAsText(text);

        assertThat(editor.getValue()).isEqualTo(UUID.fromString(text));
        assertThat(editor.getAsText()).isEqualTo(text.toLowerCase());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // UUID.fromString accepts all three as 00000001-0001-0001-0001-000000000001.
            "1-1-1-1-1",
            "+1-1-1-1-1",
            "00000001-0001-0001-0001-00000000001",
            // Missing hyphens, or none at all.
            "123e4567-e89b12d3-a456-426614174000",
            "123e4567e89b12d3a456426614174000",
            // Hyphens or separators in the wrong place.
            "123e4567-e89b-12d3-a4564-26614174000",
            "123e4567-e89b-12d3-a456_426614174000",
            // Braces, a URN prefix and Base64.
            "{123e4567-e89b-12d3-a456-426614174000}",
            "urn:uuid:123e4567-e89b-12d3-a456-426614174000",
            "EjRWeJq83vAAERIjNEVWZw==",
            // Surrounding whitespace is rejected, not trimmed.
            " 123e4567-e89b-12d3-a456-426614174000",
            "123e4567-e89b-12d3-a456-426614174000 ",
            "\t123e4567-e89b-12d3-a456-426614174000",
            "123e4567-e89b-12d3-a456-42661417400 ",
            "   ",
            // Wrong lengths.
            "123e4567-e89b-12d3-a456-42661417400",
            "123e4567-e89b-12d3-a456-4266141740000",
            "0123e4567-e89b-12d3-a456-426614174000",
            // Not hexadecimal, including a non-ASCII digit that Character.digit would accept.
            "g23e4567-e89b-12d3-a456-426614174000",
            "１23e4567-e89b-12d3-a456-426614174000",
            "not-a-uuid"
    })
    void rejectsEveryOtherFormWithoutQuotingTheValue(String text) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> editor.setAsText(text))
                .withMessage("Not a UUID in canonical 8-4-4-4-12 form");
    }

    @Test
    void treatsEmptyTextAsNoValueLikeSpringsOwnConversion() {
        editor.setAsText("");
        assertThat(editor.getValue()).isNull();
        assertThat(editor.getAsText()).isEmpty();

        editor.setAsText(null);
        assertThat(editor.getValue()).isNull();
    }
}
