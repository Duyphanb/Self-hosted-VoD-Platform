package com.vodplatform.auth.exception;

import com.vodplatform.shared.error.ApiError;
import com.vodplatform.shared.error.ApiErrorFactory;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the auth exception types only. It is intentionally not restricted to auth controllers,
 * because other modules (such as {@code user}) throw {@link CurrentUserUnavailableException}.
 * Generic mappings live in {@code com.vodplatform.shared.error.ApiExceptionHandler}.
 */
@RestControllerAdvice
public class AuthExceptionHandler {

    private final ApiErrorFactory apiErrorFactory;

    public AuthExceptionHandler(ApiErrorFactory apiErrorFactory) {
        this.apiErrorFactory = apiErrorFactory;
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    ResponseEntity<ApiError> handleDuplicateEmail(EmailAlreadyExistsException exception) {
        return apiErrorFactory.response(
                HttpStatus.CONFLICT,
                "EMAIL_ALREADY_EXISTS",
                exception.getMessage(),
                List.of()
        );
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException exception) {
        return apiErrorFactory.response(
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                exception.getMessage(),
                List.of()
        );
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    ResponseEntity<ApiError> handleInvalidRefreshToken(InvalidRefreshTokenException exception) {
        return apiErrorFactory.response(
                HttpStatus.UNAUTHORIZED,
                "INVALID_REFRESH_TOKEN",
                exception.getMessage(),
                List.of()
        );
    }

    @ExceptionHandler(CurrentUserUnavailableException.class)
    ResponseEntity<ApiError> handleCurrentUserUnavailable(CurrentUserUnavailableException exception) {
        return apiErrorFactory.response(
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                exception.getMessage(),
                List.of()
        );
    }
}
