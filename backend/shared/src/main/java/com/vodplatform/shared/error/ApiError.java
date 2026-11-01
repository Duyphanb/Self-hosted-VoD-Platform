package com.vodplatform.shared.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * The contract's error body. {@code requestId} is set only for correlated requests (see
 * {@link com.vodplatform.shared.web.RequestIds}); a null id is omitted from the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        List<ApiFieldError> fieldErrors,
        String requestId
) {

    public ApiError {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public ApiError(Instant timestamp, int status, String code, String message, List<ApiFieldError> fieldErrors) {
        this(timestamp, status, code, message, fieldErrors, null);
    }
}
