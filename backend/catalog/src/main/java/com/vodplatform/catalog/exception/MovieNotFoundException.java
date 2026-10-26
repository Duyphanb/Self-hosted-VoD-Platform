package com.vodplatform.catalog.exception;

import com.vodplatform.shared.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 404 {@code MOVIE_NOT_FOUND}. The same response covers a missing movie and a movie the caller may
 * not see (ADR-009), so it never reveals whether the movie exists.
 */
public class MovieNotFoundException extends ApiException {

    private static final long serialVersionUID = 1L;

    public MovieNotFoundException() {
        super(HttpStatus.NOT_FOUND, "MOVIE_NOT_FOUND", "Movie not found");
    }
}
