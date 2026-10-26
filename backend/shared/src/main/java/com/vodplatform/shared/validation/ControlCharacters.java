package com.vodplatform.shared.validation;

/**
 * Control-character checks reused by request validation and text normalization in every module.
 *
 * <p>A control character is a Unicode {@code Cc} code point: U+0000 to U+001F and U+007F to
 * U+009F. PostgreSQL rejects NUL in text values, which would otherwise surface as a server error,
 * and the other control characters have no place in names or titles. Only multi-line fields, such
 * as a movie description, may keep tab (U+0009), line feed (U+000A) and carriage return (U+000D).
 *
 * <p>Other invisible characters, such as zero-width or bidirectional format characters
 * ({@code Cf}), are not control characters and are not rejected here.
 */
public final class ControlCharacters {

    private ControlCharacters() {
    }

    /**
     * Returns whether the code point is a Unicode {@code Cc} control character.
     */
    public static boolean isControlCharacter(int codePoint) {
        return Character.isISOControl(codePoint);
    }

    /**
     * Returns whether the value contains any control character, including tab and line breaks.
     * Use it for single-line values.
     *
     * @param value the text to check; must not be null
     */
    public static boolean containsControlCharacter(CharSequence value) {
        return value.codePoints().anyMatch(ControlCharacters::isControlCharacter);
    }

    /**
     * Returns whether the value contains a control character other than tab, line feed or
     * carriage return. Use it for multi-line values.
     *
     * @param value the text to check; must not be null
     */
    public static boolean containsControlCharacterOtherThanTabOrLineBreak(CharSequence value) {
        return value.codePoints().anyMatch(codePoint -> isControlCharacter(codePoint) && !isTabOrLineBreak(codePoint));
    }

    private static boolean isTabOrLineBreak(int codePoint) {
        return codePoint == '\t' || codePoint == '\n' || codePoint == '\r';
    }
}
