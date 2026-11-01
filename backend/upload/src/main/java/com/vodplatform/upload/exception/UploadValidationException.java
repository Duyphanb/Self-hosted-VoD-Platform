package com.vodplatform.upload.exception;

import com.vodplatform.shared.error.ApiException;
import com.vodplatform.shared.error.ApiFieldError;

import org.springframework.http.HttpStatus;

import java.util.List;

/** Typed upload validation failure on field {@code file}; contains no rejected input. */
public final class UploadValidationException extends ApiException {

    public static final String EMPTY = "must not be empty";

    public static final String EXACTLY_ONE_PART = "must be the only part";

    public UploadValidationException() {
        this(EMPTY);
    }

    public UploadValidationException(String fieldMessage) {
        super(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Request validation failed",
                List.of(new ApiFieldError("file", fieldMessage)));
    }
}
