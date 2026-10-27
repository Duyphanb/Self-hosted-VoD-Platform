package com.vodplatform.shared.web;

import java.util.UUID;

/**
 * The only UUID text form the API accepts: the canonical 8-4-4-4-12 form of 36 characters, with
 * hyphens at fixed positions and ASCII hexadecimal digits in either case, as in
 * {@code 123e4567-e89b-12d3-a456-426614174000}.
 *
 * <p>{@link UUID#fromString(String)} alone is not enough: it accepts shorter groups and signs, so
 * {@code 1-1-1-1-1} and {@code +1-1-1-1-1} both parse as
 * {@code 00000001-0001-0001-0001-000000000001}, and Spring's own conversion also trims
 * whitespace first.
 */
final class CanonicalUuid {

    private static final int LENGTH = 36;

    private CanonicalUuid() {
    }

    /**
     * Returns whether the text is a UUID in canonical form, with no surrounding whitespace.
     *
     * @param text the text to check; must not be null
     */
    static boolean isCanonical(CharSequence text) {
        if (text.length() != LENGTH) {
            return false;
        }
        for (int index = 0; index < LENGTH; index++) {
            char character = text.charAt(index);
            boolean valid = isHyphenPosition(index) ? character == '-' : isAsciiHexDigit(character);
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses a UUID in canonical form.
     *
     * @param text the text to parse; must not be null
     * @throws IllegalArgumentException if the text is not in canonical form; the message does not
     *         quote the text
     */
    static UUID parse(String text) {
        if (!isCanonical(text)) {
            throw new IllegalArgumentException("Not a UUID in canonical 8-4-4-4-12 form");
        }
        return UUID.fromString(text);
    }

    private static boolean isHyphenPosition(int index) {
        return index == 8 || index == 13 || index == 18 || index == 23;
    }

    private static boolean isAsciiHexDigit(char character) {
        return (character >= '0' && character <= '9')
                || (character >= 'a' && character <= 'f')
                || (character >= 'A' && character <= 'F');
    }
}
