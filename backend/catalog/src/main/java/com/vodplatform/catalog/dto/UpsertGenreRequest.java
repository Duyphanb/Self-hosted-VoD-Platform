package com.vodplatform.catalog.dto;

/**
 * Admin genre payload ({@code UpsertGenreRequest} in the API contract) for create and rename. The
 * value is kept as received; {@code GenreService} validates and normalizes it and reports problems
 * as 400 {@code VALIDATION_ERROR} field errors.
 */
public record UpsertGenreRequest(String name) {
}
