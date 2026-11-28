package com.vodplatform.catalog.service;

import com.vodplatform.catalog.exception.EpisodeNotFoundException;
import com.vodplatform.catalog.persistence.Episode;
import com.vodplatform.catalog.persistence.EpisodeRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Takes the episode row lock before the upload transaction reads or inserts its assets. */
@Service
public class EpisodeRowLocks {
    private final EpisodeRepository episodes;

    public EpisodeRowLocks(EpisodeRepository episodes) {
        this.episodes = episodes;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LockedEpisode lockForMediaWrite(UUID episodeId) {
        Episode episode = episodes.findForUpdateById(episodeId).orElseThrow(EpisodeNotFoundException::new);
        return new LockedEpisode(episodeId, episode.getTitle().getId());
    }
}
