package com.vodplatform.catalog.service;

import com.vodplatform.catalog.persistence.MovieStatus;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Catalog-owned port for video availability (ADR-010). A movie is READY whenever a READY asset
 * exists; otherwise its newest asset by {@code (created_at, id)} supplies the status. Movies
 * without assets are absent from the map and have a null video status. Implementations resolve
 * a non-empty collection in one statement, and an empty collection without querying.
 */
public interface VideoStatusLookup {

    Map<UUID, String> videoStatuses(Collection<UUID> movieIds);

    Map<UUID, Availability> titleAvailability(Collection<UUID> titleIds, Collection<MovieStatus> statuses);

    record Availability(int episodes, int playableEpisodes, boolean processing) {
        public static final Availability EMPTY = new Availability(0, 0, false);

        public static Availability singleEpisode(String videoStatus) {
            return switch (videoStatus == null ? "" : videoStatus) {
                case "READY" -> new Availability(1, 1, false);
                case "UPLOADED", "QUEUED", "PROCESSING" -> new Availability(1, 0, true);
                default -> new Availability(1, 0, false);
            };
        }

        public String label() {
            if (playableEpisodes > 0) {
                return "PLAYABLE";
            }
            return processing ? "PROCESSING" : "UNAVAILABLE";
        }
    }

    default String videoStatus(UUID movieId) {
        return videoStatuses(List.of(movieId)).get(movieId);
    }
}
