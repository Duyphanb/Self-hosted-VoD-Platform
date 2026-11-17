package com.vodplatform.stream.web;

import com.vodplatform.shared.error.ApiError;
import com.vodplatform.shared.error.ApiErrorFactory;
import com.vodplatform.shared.error.ApiException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Uses the shared error factory, with a fixed safe filename in the MVC response headers so its
 * reflected-file-download suffix heuristic cannot distinguish unsafe-name 404s from other 404s.
 */
@RestControllerAdvice(assignableTypes = HlsController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HlsErrorHandler {
    private final ApiErrorFactory errors;

    public HlsErrorHandler(ApiErrorFactory errors) {
        this.errors = errors;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handle(ApiException failure) {
        return ResponseEntity.status(failure.getStatus()).header("Content-Disposition", "inline;filename=f.txt")
                .body(errors.create(failure.getStatus(), failure.getCode(), failure.getMessage(), failure.getFieldErrors()));
    }
}
