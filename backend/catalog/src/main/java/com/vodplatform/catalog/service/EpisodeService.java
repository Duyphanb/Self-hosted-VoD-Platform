package com.vodplatform.catalog.service;

import com.vodplatform.catalog.exception.EpisodeNotFoundException;
import com.vodplatform.catalog.persistence.Episode;
import com.vodplatform.catalog.persistence.EpisodeRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EpisodeService {
    private final EpisodeRepository episodes;
    private final MovieService titles;

    public EpisodeService(EpisodeRepository episodes, MovieService titles) {
        this.episodes = episodes;
        this.titles = titles;
    }

    @Transactional(readOnly = true)
    public Episode requireVisible(UUID episodeId) {
        return episodes.findVisibleById(episodeId, MovieVisibility.PUBLISHED_ONLY.statuses())
                .orElseThrow(EpisodeNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public Page<Episode> visibleEpisodes(UUID titleId, Pageable pageable) {
        titles.requireVisible(titleId, MovieVisibility.PUBLISHED_ONLY);
        return episodes.findVisibleByTitleId(titleId, MovieVisibility.PUBLISHED_ONLY.statuses(), pageable);
    }
}
