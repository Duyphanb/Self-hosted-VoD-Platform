package com.vodplatform.catalog.dto;

import com.vodplatform.catalog.persistence.MaturityRating;
import com.vodplatform.catalog.persistence.MovieStatus;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A movie detail as the API returns it ({@code MovieDetail} in the API contract): the catalog
 * fields of a {@link MovieDetail} plus the fields owned by other modules. {@code playbackProgress}
 * is omitted, as the contract specifies when the caller has no saved progress, which is always
 * the case until playback progress is implemented.
 *
 * @param posterUrl     always null: posters are deferred (ERD {@code poster_object_key})
 * @param genres        genre names ordered by genre slug
 * @param averageRating null until ratings are implemented
 * @param videoStatus   latest video availability, a {@code VideoAssetStatus} value; null when the
 *                      movie has no video asset, which is always the case until video upload is
 *                      implemented
 * @param people        credits in display order ({@code sort_order})
 * @param userRating    the caller's rating; null until ratings are implemented
 * @param inWatchlist   whether the movie is on the caller's watchlist; false until the watchlist
 *                      is implemented
 */
public record MovieDetailResponse(
        UUID id,
        String title,
        String slug,
        String description,
        Integer releaseYear,
        String posterUrl,
        List<String> genres,
        Double averageRating,
        MovieStatus status,
        String videoStatus,
        MaturityRating maturityRating,
        List<CreditedPerson> people,
        Integer userRating,
        boolean inWatchlist
) {

    public MovieDetailResponse {
        genres = List.copyOf(genres);
        people = List.copyOf(people);
    }

    /**
     * The response for a catalog detail, with the fields owned by modules that do not exist yet
     * set to null, or false for {@code inWatchlist}.
     */
    public static MovieDetailResponse of(MovieDetail detail) {
        Objects.requireNonNull(detail, "detail");
        return new MovieDetailResponse(
                detail.id(),
                detail.title(),
                detail.slug(),
                detail.description(),
                detail.releaseYear(),
                null,
                detail.genres(),
                null,
                detail.status(),
                null,
                detail.maturityRating(),
                detail.people(),
                null,
                false
        );
    }
}
