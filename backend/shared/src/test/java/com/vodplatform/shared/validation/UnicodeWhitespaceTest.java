package com.vodplatform.shared.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UnicodeWhitespaceTest {
    @Test
    void trimsUnicodeWhitespaceIncludingNoBreakSpacesAndKeepsInternalSpacing() {
        assertThat(UnicodeWhitespace.trim("\t\n\u00a0\u1680\u2007 Tên  Việt \u202f\u205f\u3000\r"))
                .isEqualTo("Tên  Việt");
        assertThat(UnicodeWhitespace.trim(" \u00a0\u2007\u202f ")).isEmpty();
        assertThat(UnicodeWhitespace.trim("")).isEmpty();
    }

    @Test
    void preservesCfAtTheEdgesAndInside() {
        assertThat(UnicodeWhitespace.trim(" \u200bTên\u202e\ufeff ")).isEqualTo("\u200bTên\u202e\ufeff");
        assertThat(UnicodeWhitespace.isWhitespace('\ufeff')).isFalse();
        assertThat(UnicodeWhitespace.isWhitespace('\u00a0')).isTrue();
    }
}
