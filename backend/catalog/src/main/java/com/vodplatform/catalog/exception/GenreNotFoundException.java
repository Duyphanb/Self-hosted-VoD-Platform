package com.vodplatform.catalog.exception;

import com.vodplatform.shared.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 404 {@code GENRE_NOT_FOUND}.
 */
public class GenreNotFoundException extends ApiException {

    private static final long serialVersionUID = 1L;

    public GenreNotFoundException() {
        super(HttpStatus.NOT_FOUND, "GENRE_NOT_FOUND", "Genre not found");
    }
}
