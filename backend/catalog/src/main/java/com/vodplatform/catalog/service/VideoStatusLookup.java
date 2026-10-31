package com.vodplatform.catalog.service;

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

    default String videoStatus(UUID movieId) {
        return videoStatuses(List.of(movieId)).get(movieId);
    }
}
