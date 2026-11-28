package com.vodplatform.catalog.dto;

import com.vodplatform.catalog.persistence.MaturityRating;
import com.vodplatform.catalog.persistence.ContentType;
import com.vodplatform.catalog.persistence.MovieStatus;
import com.vodplatform.catalog.service.VideoStatusLookup.Availability;
import java.util.List;
import java.util.UUID;

/**
 * Catalog fields of a movie detail ({@code MovieDetail} in the API contract). Fields owned by
 * other modules, such as the poster URL, average rating, the caller's rating, watchlist state
 * and playback progress, are added by the API layer ({@link MovieDetailResponse}).
 *
 * @param genres genre names ordered by genre slug
 * @param people credits in display order ({@code sort_order})
 * @param videoStatus READY if a READY asset exists, otherwise newest asset status, or null without assets
 */
public record MovieDetail(
        UUID id,
        String title,
        String slug,
        String description,
        Integer releaseYear,
        MaturityRating maturityRating,
        List<String> genres,
        List<CreditedPerson> people,
        MovieStatus status,
        String videoStatus,
        ContentType contentType,
        String partLabel,
        EpisodeSummary episode,
        Availability availability
) {

    public MovieDetail {
        genres = List.copyOf(genres);
        people = List.copyOf(people);
    }

    public MovieDetail(UUID id, String title, String slug, String description, Integer releaseYear,
                       MaturityRating maturityRating, List<String> genres, List<CreditedPerson> people,
                       MovieStatus status, String videoStatus) {
        this(id, title, slug, description, releaseYear, maturityRating, genres, people, status, videoStatus,
                ContentType.MOVIE, null, null, Availability.singleEpisode(videoStatus));
    }
}
