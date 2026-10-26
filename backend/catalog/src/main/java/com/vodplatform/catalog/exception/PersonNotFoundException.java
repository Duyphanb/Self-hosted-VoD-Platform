package com.vodplatform.catalog.exception;

import com.vodplatform.shared.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 404 {@code PERSON_NOT_FOUND}.
 */
public class PersonNotFoundException extends ApiException {

    private static final long serialVersionUID = 1L;

    public PersonNotFoundException() {
        super(HttpStatus.NOT_FOUND, "PERSON_NOT_FOUND", "Person not found");
    }
}
