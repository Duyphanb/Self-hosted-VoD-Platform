package com.vodplatform.upload.exception;

import com.vodplatform.shared.error.ApiException;
import com.vodplatform.shared.error.ApiFieldError;

import org.springframework.http.HttpStatus;

import java.util.List;

/** Typed upload validation failure; contains no rejected input. */
public final class UploadValidationException extends ApiException {
    public UploadValidationException() {
        super(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Request validation failed",
                List.of(new ApiFieldError("file", "must not be empty")));
    }
}
