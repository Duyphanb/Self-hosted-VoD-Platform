package com.vodplatform.catalog.exception;

import com.vodplatform.shared.error.ApiException;
import com.vodplatform.shared.error.ApiFieldError;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 400 {@code VALIDATION_ERROR} raised by catalog services, with the same code, message and field
 * error order as request validation failures. Field error messages never contain the rejected
 * value.
 */
public class CatalogValidationException extends ApiException {

    private static final long serialVersionUID = 1L;

    private static final Comparator<ApiFieldError> FIELD_ERROR_ORDER = Comparator
            .comparing(ApiFieldError::field)
            .thenComparing(ApiFieldError::message);

    public CatalogValidationException(List<ApiFieldError> fieldErrors) {
        super(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Request validation failed",
                fieldErrors.stream().sorted(FIELD_ERROR_ORDER).toList()
        );
    }
}
