package com.vodplatform.shared.error;

import com.vodplatform.shared.web.RequestIds;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Builds {@link ApiError} responses with timestamps taken from the application {@link Clock}.
 *
 * <p>For a correlated request (an id resolved by {@link RequestIds}) the body carries the
 * {@code requestId}, and the rejection is logged once with the status, code and id (plus the cause's
 * class name for an unexpected failure, never its message): 4xx at INFO, 5xx at WARN. Uncorrelated
 * requests keep their existing body and are not logged here.
 */
@Component
public class ApiErrorFactory {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorFactory.class);

    private final Clock clock;

    public ApiErrorFactory(Clock clock) {
        this.clock = clock;
    }

    public ApiError create(HttpStatus status, String code, String message, List<ApiFieldError> fieldErrors) {
        return create(status, code, message, fieldErrors, RequestIds.current().orElse(null));
    }

    /** Builds the body with an explicit id, for writers that run before the request context exists. */
    public ApiError create(
            HttpStatus status,
            String code,
            String message,
            List<ApiFieldError> fieldErrors,
            String requestId
    ) {
        return create(status, code, message, fieldErrors, requestId, null);
    }

    public ResponseEntity<ApiError> response(
            HttpStatus status,
            String code,
            String message,
            List<ApiFieldError> fieldErrors
    ) {
        return ResponseEntity.status(status).body(create(status, code, message, fieldErrors));
    }

    /**
     * Like {@link #response(HttpStatus, String, String, List)} for an unexpected failure: the single
     * rejection event also names the cause's class, never its message.
     */
    public ResponseEntity<ApiError> response(
            HttpStatus status,
            String code,
            String message,
            List<ApiFieldError> fieldErrors,
            Throwable cause
    ) {
        ApiError body = create(status, code, message, fieldErrors, RequestIds.current().orElse(null), cause);
        return ResponseEntity.status(status).body(body);
    }

    private ApiError create(
            HttpStatus status,
            String code,
            String message,
            List<ApiFieldError> fieldErrors,
            String requestId,
            Throwable cause
    ) {
        if (requestId != null) {
            logRejection(status, code, requestId, cause);
        }
        return new ApiError(
                clock.instant(),
                status.value(),
                code,
                message,
                fieldErrors,
                requestId
        );
    }

    private static void logRejection(HttpStatus status, String code, String requestId, Throwable cause) {
        String exceptionClass = cause == null ? null : cause.getClass().getSimpleName();
        if (status.is5xxServerError()) {
            if (exceptionClass == null) {
                log.warn("api request rejected status={} code={} requestId={}", status.value(), code, requestId);
            } else {
                log.warn(
                        "api request rejected status={} code={} requestId={} exceptionClass={}",
                        status.value(),
                        code,
                        requestId,
                        exceptionClass
                );
            }
        } else {
            log.info("api request rejected status={} code={} requestId={}", status.value(), code, requestId);
        }
    }
}
