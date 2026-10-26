package com.vodplatform.catalog.exception;

import com.vodplatform.shared.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 409 {@code GENRE_CONFLICT}: another genre already has the name or the slug derived from it.
 */
public class GenreConflictException extends ApiException {

    private static final long serialVersionUID = 1L;

    public GenreConflictException() {
        super(HttpStatus.CONFLICT, "GENRE_CONFLICT", "Another genre already has this name or slug");
    }
}
