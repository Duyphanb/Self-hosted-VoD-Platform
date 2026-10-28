package com.vodplatform.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class DisplayNameValidator implements ConstraintValidator<ValidDisplayName, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Check the raw value first: trimming must never hide a NUL, tab or line break.
        if (value == null || ControlCharacters.containsControlCharacter(value)) {
            return false;
        }
        int length = DisplayNames.trim(value).length();
        return length >= 2 && length <= 100;
    }
}
