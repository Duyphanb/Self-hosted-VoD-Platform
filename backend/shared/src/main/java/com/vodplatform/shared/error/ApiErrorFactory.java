package com.vodplatform.shared.error;

import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Builds {@link ApiError} responses with timestamps taken from the application {@link Clock}.
 */
@Component
public class ApiErrorFactory {

    private final Clock clock;

    public ApiErrorFactory(Clock clock) {
        this.clock = clock;
    }

    public ApiError create(HttpStatus status, String code, String message, List<ApiFieldError> fieldErrors) {
        return new ApiError(
                clock.instant(),
                status.value(),
                code,
                message,
                fieldErrors
        );
    }

    public ResponseEntity<ApiError> response(
            HttpStatus status,
            String code,
            String message,
            List<ApiFieldError> fieldErrors
    ) {
        return ResponseEntity.status(status).body(create(status, code, message, fieldErrors));
    }
}
