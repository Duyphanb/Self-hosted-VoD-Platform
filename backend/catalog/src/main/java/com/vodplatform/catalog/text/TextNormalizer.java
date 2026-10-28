package com.vodplatform.catalog.text;

import com.vodplatform.shared.validation.ControlCharacters;
import com.vodplatform.shared.validation.UnicodeWhitespace;

/**
 * Catalog text rules from the API contract.
 *
 * <ul>
 *   <li>Genre and person names ({@link #normalizeName}): trimmed, internal whitespace collapsed
 *       to one space; any control character, including tab and line breaks, is rejected.</li>
 *   <li>Movie title ({@link #normalizeSingleLine}): trimmed only; any control character is
 *       rejected. The contract does not collapse whitespace inside titles.</li>
 *   <li>Movie description ({@link #normalizeMultiLine}): trimmed only; tab, line feed and
 *       carriage return are kept, every other control character is rejected.</li>
 * </ul>
 *
 * <p>Every method also rejects a value that is blank after trimming. Whitespace is Unicode white
 * space, including the no-break spaces (U+00A0, U+2007, U+202F). Control characters are checked
 * on the raw value with {@link ControlCharacters}, before trimming, so a NUL at either end is
 * rejected rather than trimmed away. Length limits are request validation, not part of this class.
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /**
     * Normalizes a genre or person name: rejects control characters, collapses each run of
     * whitespace to one space and trims.
     *
     * @param value the raw name; must not be null
     * @throws InvalidTextException if the value contains a control character or is blank
     */
    public static String normalizeName(String value) {
        if (ControlCharacters.containsControlCharacter(value)) {
            throw new InvalidTextException(InvalidTextException.Reason.CONTROL_CHARACTER);
        }
        return requireNotBlank(collapseWhitespace(UnicodeWhitespace.trim(value)));
    }

    /**
     * Normalizes a single-line value such as a movie title: rejects control characters and trims.
     *
     * @param value the raw value; must not be null
     * @throws InvalidTextException if the value contains a control character or is blank
     */
    public static String normalizeSingleLine(String value) {
        if (ControlCharacters.containsControlCharacter(value)) {
            throw new InvalidTextException(InvalidTextException.Reason.CONTROL_CHARACTER);
        }
        return requireNotBlank(UnicodeWhitespace.trim(value));
    }

    /**
     * Normalizes a multi-line value such as a movie description: rejects control characters
     * other than tab, line feed and carriage return, and trims.
     *
     * @param value the raw value; must not be null
     * @throws InvalidTextException if the value contains a disallowed control character or is
     *                              blank
     */
    public static String normalizeMultiLine(String value) {
        if (ControlCharacters.containsControlCharacterOtherThanTabOrLineBreak(value)) {
            throw new InvalidTextException(InvalidTextException.Reason.CONTROL_CHARACTER);
        }
        return requireNotBlank(UnicodeWhitespace.trim(value));
    }

    private static String requireNotBlank(String value) {
        if (value.isEmpty()) {
            throw new InvalidTextException(InvalidTextException.Reason.BLANK);
        }
        return value;
    }

    private static String collapseWhitespace(String value) {
        StringBuilder collapsed = new StringBuilder(value.length());
        boolean previousWasWhitespace = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (UnicodeWhitespace.isWhitespace(character)) {
                if (!previousWasWhitespace) {
                    collapsed.append(' ');
                }
                previousWasWhitespace = true;
            } else {
                collapsed.append(character);
                previousWasWhitespace = false;
            }
        }
        return collapsed.toString();
    }

}
