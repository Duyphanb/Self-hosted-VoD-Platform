package com.vodplatform.auth.web;

import com.vodplatform.auth.security.AuthStateUnavailableException;
import com.vodplatform.shared.error.ApiError;
import com.vodplatform.shared.error.ApiErrorFactory;
import jakarta.persistence.PersistenceException;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {LoginController.class, LogoutController.class, RefreshTokenController.class})
public class AuthStateExceptionHandler {

    private final ApiErrorFactory errors;

    public AuthStateExceptionHandler(ApiErrorFactory errors) {
        this.errors = errors;
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class, PersistenceException.class})
    ResponseEntity<ApiError> unavailable() {
        return errors.response(HttpStatus.SERVICE_UNAVAILABLE, AuthStateUnavailableException.CODE,
                AuthStateUnavailableException.MESSAGE, List.of());
    }
}
