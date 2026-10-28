package com.vodplatform.shared.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class DisplayNameValidatorTest {
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    void reportsOneSpecificViolationWithoutEchoingInput(String value, String message) {
        assertThat(validator.validate(new Request(value)))
                .singleElement().satisfies(violation -> {
                    assertThat(violation.getPropertyPath().toString()).isEqualTo("displayName");
                    assertThat(violation.getMessage()).isEqualTo(message);
                });
    }

    static Stream<Arguments> invalidNames() {
        String size = "size must be between 2 and 100";
        String control = "must not contain control characters";
        return Stream.of(
                Arguments.of(null, "must not be null"),
                Arguments.of("", "must not be blank"),
                Arguments.of(" \u00a0\u2007\u202f ", "must not be blank"),
                Arguments.of("\u0000Viewer", control),
                Arguments.of("Viewer\u0000", control),
                Arguments.of("View\rer", control),
                Arguments.of("View\ner", control),
                Arguments.of("A", size),
                Arguments.of(" A ", size),
                Arguments.of("😀", size),
                Arguments.of(" 😀 ", size),
                Arguments.of("A".repeat(101), size),
                Arguments.of(" " + "A".repeat(100) + " ", size),
                Arguments.of(" ".repeat(10000) + "AB", size),
                Arguments.of("😀".repeat(101), size));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 51, 100})
    void acceptsCodePointBoundariesWithAstralCharacters(int count) {
        assertThat(validator.validate(new Request("😀".repeat(count)))).isEmpty();
    }

    private record Request(@ValidDisplayName String displayName) {
    }
}
