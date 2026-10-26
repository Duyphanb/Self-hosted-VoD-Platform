package com.vodplatform.catalog.service;

import com.vodplatform.catalog.dto.MovieCreditInput;
import com.vodplatform.catalog.dto.UpsertMovieRequest;
import com.vodplatform.catalog.persistence.MaturityRating;
import com.vodplatform.catalog.persistence.MovieStatus;
import java.util.List;

/**
 * Builds an {@link UpsertMovieRequest} that is valid by default, with one genre and no credits;
 * each setter replaces one value.
 */
final class MovieRequestBuilder {

    private String title = "Đất Rừng Phương Nam";
    private String slug = "dat-rung-phuong-nam";
    private String description = "Mô tả";
    private Integer releaseYear = 2023;
    private MaturityRating maturityRating = MaturityRating.T13;
    private List<String> genres = List.of("Hành Động");
    private List<MovieCreditInput> people = List.of();
    private MovieStatus status = MovieStatus.PUBLISHED;

    MovieRequestBuilder title(String value) {
        title = value;
        return this;
    }

    MovieRequestBuilder slug(String value) {
        slug = value;
        return this;
    }

    MovieRequestBuilder description(String value) {
        description = value;
        return this;
    }

    MovieRequestBuilder releaseYear(Integer value) {
        releaseYear = value;
        return this;
    }

    MovieRequestBuilder maturityRating(MaturityRating value) {
        maturityRating = value;
        return this;
    }

    MovieRequestBuilder genres(List<String> value) {
        genres = value;
        return this;
    }

    MovieRequestBuilder people(List<MovieCreditInput> value) {
        people = value;
        return this;
    }

    MovieRequestBuilder status(MovieStatus value) {
        status = value;
        return this;
    }

    UpsertMovieRequest build() {
        return new UpsertMovieRequest(title, slug, description, releaseYear, maturityRating, genres, people, status);
    }
}
