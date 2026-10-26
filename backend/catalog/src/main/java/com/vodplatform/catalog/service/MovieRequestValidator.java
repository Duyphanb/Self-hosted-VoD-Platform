package com.vodplatform.catalog.service;

import com.vodplatform.catalog.dto.MovieCreditInput;
import com.vodplatform.catalog.dto.UpsertMovieRequest;
import com.vodplatform.catalog.exception.CatalogValidationException;
import com.vodplatform.catalog.service.ValidatedMovie.CreditInput;
import com.vodplatform.catalog.service.ValidatedMovie.GenreInput;
import com.vodplatform.catalog.text.SlugNormalizer;
import com.vodplatform.catalog.text.TextNormalizer;
import com.vodplatform.shared.error.ApiFieldError;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Validates an {@link UpsertMovieRequest} against the API contract limits and text rules, without
 * database access. All problems are collected and reported together as one
 * {@link CatalogValidationException}.
 *
 * <p>Lengths are the contract's {@code maxLength}, counted in Unicode code points on the value as
 * received, before trimming. Text is normalized with {@link TextNormalizer}; the slug is checked as
 * given and never rewritten. Rules that need the database (unknown {@code personId}, duplicate
 * (person, role) pairs after resolution) are checked when credits are resolved.
 */
final class MovieRequestValidator {

    static final int TITLE_MAX_LENGTH = 255;
    static final int DESCRIPTION_MAX_LENGTH = 5000;
    static final int MIN_RELEASE_YEAR = 1888;
    static final int MAX_RELEASE_YEAR = 2100;
    static final int MAX_GENRES = 10;
    static final int GENRE_NAME_MAX_LENGTH = 100;
    static final int MAX_PEOPLE = 50;
    static final int PERSON_NAME_MAX_LENGTH = 255;

    static final String NOT_NULL = TextFields.NOT_NULL;
    static final String SLUG_PATTERN = "must match \"^[a-z0-9]+(-[a-z0-9]+)*$\"";
    static final String RELEASE_YEAR_RANGE = "must be between " + MIN_RELEASE_YEAR + " and " + MAX_RELEASE_YEAR;
    static final String GENRE_WITHOUT_SLUG = "must contain at least one letter or digit";
    static final String DUPLICATE_GENRE = "must not name the same genre as another entry";
    static final String PERSON_ID_OR_NAME = "must have a personId or a name";

    private MovieRequestValidator() {
    }

    /**
     * @throws CatalogValidationException if any value breaks a contract rule
     */
    static ValidatedMovie validate(UpsertMovieRequest request) {
        Objects.requireNonNull(request, "request");
        List<ApiFieldError> errors = new ArrayList<>();

        String title = text(errors, "title", request.title(), TITLE_MAX_LENGTH, TextNormalizer::normalizeSingleLine);
        String slug = slug(errors, request.slug());
        String description = text(
                errors, "description", request.description(), DESCRIPTION_MAX_LENGTH, TextNormalizer::normalizeMultiLine);
        Integer releaseYear = request.releaseYear();
        if (releaseYear != null && (releaseYear < MIN_RELEASE_YEAR || releaseYear > MAX_RELEASE_YEAR)) {
            errors.add(new ApiFieldError("releaseYear", RELEASE_YEAR_RANGE));
        }
        if (request.status() == null) {
            errors.add(new ApiFieldError("status", NOT_NULL));
        }
        List<GenreInput> genres = genres(errors, request.genres());
        List<CreditInput> people = people(errors, request.people());

        if (!errors.isEmpty()) {
            throw new CatalogValidationException(errors);
        }
        return new ValidatedMovie(
                title, slug, description, releaseYear, request.maturityRating(), request.status(), genres, people);
    }

    private static String slug(List<ApiFieldError> errors, String slug) {
        if (slug == null) {
            errors.add(new ApiFieldError("slug", NOT_NULL));
        } else if (length(slug) > SlugNormalizer.MOVIE_SLUG_MAX_LENGTH) {
            errors.add(new ApiFieldError("slug", size(1, SlugNormalizer.MOVIE_SLUG_MAX_LENGTH)));
        } else if (!SlugNormalizer.isSlug(slug)) {
            errors.add(new ApiFieldError("slug", SLUG_PATTERN));
        } else {
            return slug;
        }
        return null;
    }

    private static List<GenreInput> genres(List<ApiFieldError> errors, List<String> names) {
        if (names == null) {
            errors.add(new ApiFieldError("genres", NOT_NULL));
            return List.of();
        }
        if (names.isEmpty() || names.size() > MAX_GENRES) {
            errors.add(new ApiFieldError("genres", size(1, MAX_GENRES)));
            return List.of();
        }
        List<GenreInput> genres = new ArrayList<>(names.size());
        Set<String> slugs = new HashSet<>();
        for (int index = 0; index < names.size(); index++) {
            String field = "genres[" + index + "]";
            String name = text(errors, field, names.get(index), GENRE_NAME_MAX_LENGTH, TextNormalizer::normalizeName);
            if (name == null) {
                continue;
            }
            String slug = SlugNormalizer.slugify(name);
            if (slug.isEmpty()) {
                errors.add(new ApiFieldError(field, GENRE_WITHOUT_SLUG));
            } else if (!slugs.add(slug)) {
                errors.add(new ApiFieldError(field, DUPLICATE_GENRE));
            } else {
                genres.add(new GenreInput(name, slug));
            }
        }
        return genres;
    }

    private static List<CreditInput> people(List<ApiFieldError> errors, List<MovieCreditInput> credits) {
        if (credits == null) {
            // Required even when empty, so that credits are never cleared by omission.
            errors.add(new ApiFieldError("people", NOT_NULL));
            return List.of();
        }
        if (credits.size() > MAX_PEOPLE) {
            errors.add(new ApiFieldError("people", size(0, MAX_PEOPLE)));
            return List.of();
        }
        List<CreditInput> people = new ArrayList<>(credits.size());
        for (int index = 0; index < credits.size(); index++) {
            String field = "people[" + index + "]";
            MovieCreditInput credit = credits.get(index);
            if (credit == null) {
                errors.add(new ApiFieldError(field, NOT_NULL));
                continue;
            }
            if (credit.role() == null) {
                errors.add(new ApiFieldError(field + ".role", NOT_NULL));
            }
            if (credit.personId() != null) {
                // The personId identifies the person; the contract ignores the name.
                people.add(new CreditInput(index, credit.personId(), null, credit.role()));
            } else if (credit.name() == null) {
                errors.add(new ApiFieldError(field, PERSON_ID_OR_NAME));
            } else {
                String name = text(
                        errors, field + ".name", credit.name(), PERSON_NAME_MAX_LENGTH, TextNormalizer::normalizeName);
                people.add(new CreditInput(index, null, name, credit.role()));
            }
        }
        return people;
    }

    private static String text(
            List<ApiFieldError> errors,
            String field,
            String value,
            int maxLength,
            UnaryOperator<String> normalizer
    ) {
        return TextFields.required(errors, field, value, maxLength, normalizer);
    }

    static String size(int min, int max) {
        return TextFields.size(min, max);
    }

    private static int length(String value) {
        return TextFields.length(value);
    }
}
