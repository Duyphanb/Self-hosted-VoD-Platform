package com.vodplatform.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class DisplayNameValidator implements ConstraintValidator<ValidDisplayName, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return invalid(context, "must not be null");
        }
        // Bound the received value in code points, as in the catalog and JSON Schema.
        if (value.codePointCount(0, value.length()) > 100) {
            return invalid(context, "size must be between 2 and 100");
        }
        // Check raw controls before trimming can hide a NUL, tab or line break.
        if (ControlCharacters.containsControlCharacter(value)) {
            return invalid(context, "must not contain control characters");
        }
        String trimmed = UnicodeWhitespace.trim(value);
        if (trimmed.isEmpty()) {
            return invalid(context, "must not be blank");
        }
        if (trimmed.codePointCount(0, trimmed.length()) < 2) {
            return invalid(context, "size must be between 2 and 100");
        }
        return true;
    }

    private boolean invalid(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
