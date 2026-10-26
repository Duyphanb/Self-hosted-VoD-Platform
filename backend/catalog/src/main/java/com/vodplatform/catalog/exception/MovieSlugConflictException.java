package com.vodplatform.catalog.exception;

import com.vodplatform.shared.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 409 {@code MOVIE_SLUG_CONFLICT}: another movie already uses the slug.
 */
public class MovieSlugConflictException extends ApiException {

    private static final long serialVersionUID = 1L;

    public MovieSlugConflictException() {
        super(HttpStatus.CONFLICT, "MOVIE_SLUG_CONFLICT", "Slug is already used by another movie");
    }
}
