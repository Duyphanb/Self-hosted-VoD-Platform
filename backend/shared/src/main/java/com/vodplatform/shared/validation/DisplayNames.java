package com.vodplatform.shared.validation;

/** Display-name whitespace handling, aligned with the catalog's Unicode whitespace rule. */
public final class DisplayNames {
    private DisplayNames() {
    }

    /** Trim validated values before persistence; preserve internal spacing and all Cf characters. */
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

    private static boolean isWhitespace(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
    }
}
