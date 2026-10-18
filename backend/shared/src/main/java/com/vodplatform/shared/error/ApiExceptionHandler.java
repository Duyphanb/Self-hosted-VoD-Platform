package com.vodplatform.shared.error;

import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Generic API error mappings shared by every feature module. Feature-specific exception types
 * are handled by their own module advice; no exception type may be handled by two advices.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private final ApiErrorFactory apiErrorFactory;

    public ApiExceptionHandler(ApiErrorFactory apiErrorFactory) {
        this.apiErrorFactory = apiErrorFactory;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        List<ApiFieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiFieldError(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(ApiFieldError::field))
                .toList();

        return apiErrorFactory.response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Request validation failed",
                fieldErrors
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableRequest() {
        return apiErrorFactory.response(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "Request body is missing or malformed",
                List.of()
        );
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApiException(ApiException exception) {
        return apiErrorFactory.response(
                exception.getStatus(),
                exception.getCode(),
                exception.getMessage(),
                exception.getFieldErrors()
        );
    }
}
