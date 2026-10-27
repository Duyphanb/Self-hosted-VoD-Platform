package com.vodplatform.catalog.service;

import com.vodplatform.catalog.dto.UpsertGenreRequest;
import com.vodplatform.catalog.dto.UpsertPersonRequest;
import com.vodplatform.catalog.exception.CatalogValidationException;
import com.vodplatform.catalog.service.ValidatedMovie.GenreInput;
import com.vodplatform.catalog.text.SlugNormalizer;
import com.vodplatform.catalog.text.TextNormalizer;
import com.vodplatform.shared.error.ApiFieldError;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Validates the admin genre and person payloads, the people search term and the movie list genre
 * filter against the API contract, without database access, with the same rules and messages as
 * the genre and credit names of a movie request.
 */
final class NameRequestValidator {

    static final int SEARCH_TERM_MAX_LENGTH = 100;

    /** Maximum length of the movie list {@code genre} filter (API contract). */
    static final int GENRE_FILTER_MAX_LENGTH = 120;

    private static final String NAME = "name";
    private static final String SEARCH_TERM = "q";
    private static final String GENRE_FILTER = "genre";

    private NameRequestValidator() {
    }

    /**
     * Returns the normalized genre name and its slug, the genre match key.
     *
     * @throws CatalogValidationException if the name is missing, too long, blank, contains a control
     *                                    character or has an empty slug
     */
    static GenreInput genre(UpsertGenreRequest request) {
        Objects.requireNonNull(request, "request");
        List<ApiFieldError> errors = new ArrayList<>();
        String name = TextFields.required(
                errors, NAME, request.name(), MovieRequestValidator.GENRE_NAME_MAX_LENGTH, TextNormalizer::normalizeName);
        String slug = name == null ? null : SlugNormalizer.slugify(name);
        if (slug != null && slug.isEmpty()) {
            errors.add(new ApiFieldError(NAME, MovieRequestValidator.GENRE_WITHOUT_SLUG));
        }
        if (!errors.isEmpty()) {
            throw new CatalogValidationException(errors);
        }
        return new GenreInput(name, slug);
    }

    /**
     * Returns the normalized person name, the value stored and compared by name-only credits.
     *
     * @throws CatalogValidationException if the name is missing, too long, blank or contains a
     *                                    control character
     */
    static String personName(UpsertPersonRequest request) {
        Objects.requireNonNull(request, "request");
        List<ApiFieldError> errors = new ArrayList<>();
        String name = TextFields.required(
                errors, NAME, request.name(), MovieRequestValidator.PERSON_NAME_MAX_LENGTH, TextNormalizer::normalizeName);
        if (!errors.isEmpty()) {
            throw new CatalogValidationException(errors);
        }
        return name;
    }

    /**
     * Returns the people search term normalized with the slug rule, or null when the term is absent
     * or empty after normalization (the contract treats both as no filter).
     *
     * @throws CatalogValidationException if the term is longer than
     *                                    {@value #SEARCH_TERM_MAX_LENGTH} characters
     */
    static String searchSlug(String term) {
        if (term == null) {
            return null;
        }
        if (TextFields.length(term) > SEARCH_TERM_MAX_LENGTH) {
            throw new CatalogValidationException(
                    List.of(new ApiFieldError(SEARCH_TERM, TextFields.size(0, SEARCH_TERM_MAX_LENGTH))));
        }
        String slug = SlugNormalizer.slugify(term);
        return slug.isEmpty() ? null : slug;
    }

    /**
     * Returns the {@code genre} filter of the movie list normalized with the slug rule, the genre
     * match key, or null when the filter is absent. Unlike the people search term, a present value
     * must name a genre: an empty value or one whose slug is empty is rejected.
     *
     * @throws CatalogValidationException if the value is empty, longer than
     *                                    {@value #GENRE_FILTER_MAX_LENGTH} characters, or has an
     *                                    empty slug
     */
    static String genreFilterSlug(String genre) {
        if (genre == null) {
            return null;
        }
        int length = TextFields.length(genre);
        if (length < 1 || length > GENRE_FILTER_MAX_LENGTH) {
            throw new CatalogValidationException(
                    List.of(new ApiFieldError(GENRE_FILTER, TextFields.size(1, GENRE_FILTER_MAX_LENGTH))));
        }
        String slug = SlugNormalizer.slugify(genre);
        if (slug.isEmpty()) {
            throw new CatalogValidationException(
                    List.of(new ApiFieldError(GENRE_FILTER, MovieRequestValidator.GENRE_WITHOUT_SLUG)));
        }
        return slug;
    }
}
