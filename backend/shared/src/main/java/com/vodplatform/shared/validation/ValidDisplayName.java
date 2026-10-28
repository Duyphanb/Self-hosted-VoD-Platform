package com.vodplatform.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Shared registration/profile rule; length is measured after Unicode whitespace trimming. */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DisplayNameValidator.class)
public @interface ValidDisplayName {
    String message() default "must contain 2 to 100 characters after trimming and no control characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
