package com.vodplatform.shared.error;

import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/**
 * Business failure that {@link ApiExceptionHandler} maps to an {@link ApiError} response with the
 * given status, code, message and optional field errors.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ApiFieldError> fieldErrors;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<ApiFieldError> fieldErrors) {
        super(Objects.requireNonNull(message, "message"));
        this.status = Objects.requireNonNull(status, "status");
        this.code = Objects.requireNonNull(code, "code");
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public List<ApiFieldError> getFieldErrors() {
        return fieldErrors;
    }
}
