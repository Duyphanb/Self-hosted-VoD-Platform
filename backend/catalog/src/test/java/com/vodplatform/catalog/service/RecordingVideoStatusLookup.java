package com.vodplatform.catalog.service;

import com.vodplatform.catalog.persistence.MovieStatus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** SQL-free port stub: catalog tests check batching; encoding tests check the SQL statement count. */
final class RecordingVideoStatusLookup implements VideoStatusLookup {

    final List<List<UUID>> calls = new ArrayList<>();
    final Map<UUID, String> statuses = new HashMap<>();

    @Override
    public Map<UUID, String> videoStatuses(Collection<UUID> movieIds) {
        calls.add(List.copyOf(movieIds));
        Map<UUID, String> result = new HashMap<>();
        for (UUID movieId : movieIds) {
            if (statuses.containsKey(movieId)) {
                result.put(movieId, statuses.get(movieId));
            }
        }
        return Map.copyOf(result);
    }

    @Override
    public Map<UUID, Availability> titleAvailability(Collection<UUID> titleIds, Collection<MovieStatus> visibility) {
        Map<UUID, Availability> result = new HashMap<>();
        videoStatuses(titleIds).forEach((id, status) -> result.put(id, new Availability(1,
                "READY".equals(status) ? 1 : 0, List.of("UPLOADED", "QUEUED", "PROCESSING").contains(status))));
        return Map.copyOf(result);
    }

    void clear() {
        calls.clear();
        statuses.clear();
    }
}
