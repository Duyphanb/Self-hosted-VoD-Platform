package com.vodplatform.shared.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ControlCharactersTest {

    @Test
    void classifiesExactlyTheUnicodeCcRangesAsControlCharacters() {
        assertThat(IntStream.rangeClosed(0x00, 0x1F)).allMatch(ControlCharacters::isControlCharacter);
        assertThat(IntStream.rangeClosed(0x7F, 0x9F)).allMatch(ControlCharacters::isControlCharacter);

        assertThat(IntStream.rangeClosed(0x20, 0x7E)).noneMatch(ControlCharacters::isControlCharacter);
        assertThat(ControlCharacters.isControlCharacter(0xA0)).isFalse();
        assertThat(ControlCharacters.isControlCharacter(0x200B)).isFalse();
        assertThat(ControlCharacters.isControlCharacter(0x2028)).isFalse();
        assertThat(ControlCharacters.isControlCharacter(0x1F600)).isFalse();
    }

    @Test
    void singleLineCheckRejectsEveryControlCharacterIncludingTabAndLineBreaks() {
        assertThat(ControlCharacters.containsControlCharacter("Đất Rừng Phương Nam")).isFalse();
        assertThat(ControlCharacters.containsControlCharacter("")).isFalse();

        assertThat(ControlCharacters.containsControlCharacter("Phim\u0000")).isTrue();
        assertThat(ControlCharacters.containsControlCharacter("a\tb")).isTrue();
        assertThat(ControlCharacters.containsControlCharacter("a\nb")).isTrue();
        assertThat(ControlCharacters.containsControlCharacter("a\rb")).isTrue();
        assertThat(ControlCharacters.containsControlCharacter("a\u001Fb")).isTrue();
        assertThat(ControlCharacters.containsControlCharacter("a\u007Fb")).isTrue();
        assertThat(ControlCharacters.containsControlCharacter("a\u0085b")).isTrue();
        assertThat(ControlCharacters.containsControlCharacter("a\u009Fb")).isTrue();
    }

    @Test
    void multiLineCheckAllowsOnlyTabAndLineBreaks() {
        assertThat(ControlCharacters.containsControlCharacterOtherThanTabOrLineBreak("Dòng 1\r\nDòng 2\tcột"))
                .isFalse();

        assertThat(ControlCharacters.containsControlCharacterOtherThanTabOrLineBreak("a\u0000b")).isTrue();
        assertThat(ControlCharacters.containsControlCharacterOtherThanTabOrLineBreak("a\u000Bb")).isTrue();
        assertThat(ControlCharacters.containsControlCharacterOtherThanTabOrLineBreak("a\u000Cb")).isTrue();
        assertThat(ControlCharacters.containsControlCharacterOtherThanTabOrLineBreak("a\u001Bb")).isTrue();
        assertThat(ControlCharacters.containsControlCharacterOtherThanTabOrLineBreak("a\u007Fb")).isTrue();
        assertThat(ControlCharacters.containsControlCharacterOtherThanTabOrLineBreak("a\u0085b")).isTrue();
    }

    @Test
    void requiresAValue() {
        assertThatNullPointerException().isThrownBy(() -> ControlCharacters.containsControlCharacter(null));
        assertThatNullPointerException()
                .isThrownBy(() -> ControlCharacters.containsControlCharacterOtherThanTabOrLineBreak(null));
    }
}
