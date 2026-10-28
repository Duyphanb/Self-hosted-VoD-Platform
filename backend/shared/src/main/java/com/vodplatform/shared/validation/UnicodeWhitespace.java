package com.vodplatform.shared.validation;

/** Unicode whitespace handling shared by catalog normalization and display-name validation. */
public final class UnicodeWhitespace {
    private UnicodeWhitespace() {
    }

    /** Trim whitespace at the edges; preserve internal spacing and all Cf characters. */
    public static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    /** Java whitespace plus no-break spaces; every such character is in the BMP. */
    public static boolean isWhitespace(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
    }
}
