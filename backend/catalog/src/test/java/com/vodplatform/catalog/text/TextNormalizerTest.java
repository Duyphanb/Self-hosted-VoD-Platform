package com.vodplatform.catalog.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class TextNormalizerTest {

    @Test
    void namesAreTrimmedAndInternalWhitespaceIsCollapsed() {
        assertThat(TextNormalizer.normalizeName("  Đất   Rừng  Phương Nam ")).isEqualTo("Đất Rừng Phương Nam");
        assertThat(TextNormalizer.normalizeName("Trấn Thành")).isEqualTo("Trấn Thành");
        assertThat(TextNormalizer.normalizeName("　Hài   Hước ")).isEqualTo("Hài Hước");
        assertThat(TextNormalizer.normalizeName("Hành Động")).isEqualTo("Hành Động");
    }

    @Test
    void namesKeepCaseAndDiacritics() {
        assertThat(TextNormalizer.normalizeName("hài")).isEqualTo("hài");
        assertThat(TextNormalizer.normalizeName("HÀI")).isEqualTo("HÀI");
    }

    @Test
    void singleLineValuesAreTrimmedWithoutCollapsingInternalWhitespace() {
        assertThat(TextNormalizer.normalizeSingleLine("  Đất Rừng  Phương Nam ")).isEqualTo("Đất Rừng  Phương Nam");
    }

    @Test
    void multiLineValuesKeepTabsAndLineBreaksInsideAndAreTrimmed() {
        String description = "\r\n  Dòng 1\r\n\tDòng 2\n\nDòng 3  \n\t";

        assertThat(TextNormalizer.normalizeMultiLine(description)).isEqualTo("Dòng 1\r\n\tDòng 2\n\nDòng 3");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\t", "\n", "\r"})
    void singleLineFieldsRejectTabsAndLineBreaks(String character) {
        String value = "Phim" + character + "Hay";

        assertRejected(TextNormalizer::normalizeName, value, InvalidTextException.Reason.CONTROL_CHARACTER);
        assertRejected(TextNormalizer::normalizeSingleLine, value, InvalidTextException.Reason.CONTROL_CHARACTER);
        assertThat(TextNormalizer.normalizeMultiLine(value)).isEqualTo(value);
    }

    @ParameterizedTest
    @MethodSource("controlCharactersOtherThanTabAndLineBreaks")
    void everyFieldRejectsOtherControlCharacters(int codePoint) {
        String value = "Phim" + Character.toString(codePoint) + "Hay";

        assertRejected(TextNormalizer::normalizeName, value, InvalidTextException.Reason.CONTROL_CHARACTER);
        assertRejected(TextNormalizer::normalizeSingleLine, value, InvalidTextException.Reason.CONTROL_CHARACTER);
        assertRejected(TextNormalizer::normalizeMultiLine, value, InvalidTextException.Reason.CONTROL_CHARACTER);
    }

    @Test
    void controlCharactersAtEitherEndAreRejectedRatherThanTrimmed() {
        assertRejected(TextNormalizer::normalizeName, "\u0000Phim", InvalidTextException.Reason.CONTROL_CHARACTER);
        assertRejected(TextNormalizer::normalizeSingleLine, "Phim\u0000", InvalidTextException.Reason.CONTROL_CHARACTER);
        assertRejected(TextNormalizer::normalizeMultiLine, "Phim\u001F", InvalidTextException.Reason.CONTROL_CHARACTER);
        assertRejected(TextNormalizer::normalizeName, "Phim\n", InvalidTextException.Reason.CONTROL_CHARACTER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", " ", "　 "})
    void everyFieldRejectsBlankValues(String value) {
        assertRejected(TextNormalizer::normalizeName, value, InvalidTextException.Reason.BLANK);
        assertRejected(TextNormalizer::normalizeSingleLine, value, InvalidTextException.Reason.BLANK);
        assertRejected(TextNormalizer::normalizeMultiLine, value, InvalidTextException.Reason.BLANK);
    }

    @Test
    void multiLineValuesMadeOnlyOfTabsAndLineBreaksAreBlank() {
        assertRejected(TextNormalizer::normalizeMultiLine, "\r\n\t \n", InvalidTextException.Reason.BLANK);
    }

    @Test
    void rejectionMessagesDoNotEchoTheValue() {
        InvalidTextException exception = catchThrowableOfType(
                InvalidTextException.class,
                () -> TextNormalizer.normalizeName("secret\u0000value")
        );

        assertThat(exception).isInstanceOf(IllegalArgumentException.class);
        assertThat(exception.getMessage()).doesNotContain("secret").doesNotContain("value");
    }

    @Test
    void requiresAValue() {
        assertThatNullPointerException().isThrownBy(() -> TextNormalizer.normalizeName(null));
        assertThatNullPointerException().isThrownBy(() -> TextNormalizer.normalizeSingleLine(null));
        assertThatNullPointerException().isThrownBy(() -> TextNormalizer.normalizeMultiLine(null));
    }

    static Stream<Integer> controlCharactersOtherThanTabAndLineBreaks() {
        return Stream.concat(IntStream.rangeClosed(0x00, 0x1F).boxed(), IntStream.rangeClosed(0x7F, 0x9F).boxed())
                .filter(codePoint -> codePoint != '\t' && codePoint != '\n' && codePoint != '\r');
    }

    private static void assertRejected(
            UnaryOperator<String> normalizer,
            String value,
            InvalidTextException.Reason reason
    ) {
        InvalidTextException exception = catchThrowableOfType(InvalidTextException.class, () -> normalizer.apply(value));

        assertThat(exception).as("rejection of %s", value.codePoints().boxed().toList()).isNotNull();
        assertThat(exception.getReason()).isEqualTo(reason);
    }
}
