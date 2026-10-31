package com.vodplatform.encoding.service;

import com.vodplatform.catalog.service.VideoStatusLookup;
import com.vodplatform.encoding.persistence.VideoAssetRepository;
import com.vodplatform.encoding.persistence.VideoAssetStatus;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves catalog availability in one set-based statement, without a catalog-to-encoding edge. */
@Service
@Transactional(readOnly = true)
public class EncodingVideoStatusLookup implements VideoStatusLookup {

    private final VideoAssetRepository assets;

    public EncodingVideoStatusLookup(VideoAssetRepository assets) {
        this.assets = assets;
    }

    @Override
    public Map<UUID, String> videoStatuses(Collection<UUID> movieIds) {
        Objects.requireNonNull(movieIds, "movieIds");
        if (movieIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> statuses = new HashMap<>();
        for (Object[] row : assets.findVideoStatusRows(movieIds)) {
            UUID movieId = row[0] instanceof UUID uuid ? uuid : UUID.fromString(row[0].toString());
            statuses.put(movieId, VideoAssetStatus.valueOf(row[1].toString()).name());
        }
        return Map.copyOf(statuses);
    }
}
