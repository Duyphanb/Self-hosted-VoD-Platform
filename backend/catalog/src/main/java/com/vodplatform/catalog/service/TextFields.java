package com.vodplatform.catalog.service;

import com.vodplatform.catalog.text.InvalidTextException;
import com.vodplatform.catalog.text.TextNormalizer;
import com.vodplatform.shared.error.ApiFieldError;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Checks of required text fields shared by the catalog request validators. Lengths are the
 * contract's {@code maxLength}, counted in Unicode code points on the value as received, before
 * trimming; the value is then normalized with a {@link TextNormalizer} method. Messages never
 * contain the rejected value.
 */
final class TextFields {

    static final String NOT_NULL = "must not be null";
    static final String NOT_BLANK = "must not be blank";
    static final String NO_CONTROL_CHARACTERS = "must not contain control characters";

    private TextFields() {
    }

    /**
     * Checks a required text value and returns it normalized, or records an error and returns
     * null.
     */
    static String required(
            List<ApiFieldError> errors,
            String field,
            String value,
            int maxLength,
            UnaryOperator<String> normalizer
    ) {
        if (value == null) {
            errors.add(new ApiFieldError(field, NOT_NULL));
            return null;
        }
        if (length(value) > maxLength) {
            errors.add(new ApiFieldError(field, size(1, maxLength)));
            return null;
        }
        try {
            return normalizer.apply(value);
        } catch (InvalidTextException exception) {
            String message = exception.getReason() == InvalidTextException.Reason.BLANK
                    ? NOT_BLANK
                    : NO_CONTROL_CHARACTERS;
            errors.add(new ApiFieldError(field, message));
            return null;
        }
    }

    static String size(int min, int max) {
        return "size must be between " + min + " and " + max;
    }

    /**
     * Length in Unicode code points.
     */
    static int length(String value) {
        return value.codePointCount(0, value.length());
    }
}
