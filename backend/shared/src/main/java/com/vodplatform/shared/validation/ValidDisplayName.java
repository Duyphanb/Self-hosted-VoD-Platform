package com.vodplatform.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Shared rule: at most 100 raw code points, no Cc, and at least 2 code points after trimming. */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DisplayNameValidator.class)
public @interface ValidDisplayName {
    String message() default "invalid display name";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
