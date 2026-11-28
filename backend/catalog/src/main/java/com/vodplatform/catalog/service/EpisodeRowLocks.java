package com.vodplatform.catalog.service;

import com.vodplatform.catalog.exception.EpisodeNotFoundException;
import com.vodplatform.catalog.persistence.Episode;
import com.vodplatform.catalog.persistence.EpisodeRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Per-episode transaction serialization shared by upload commit and retry (ADR-010, ADR-013 D5). */
@Service
public class EpisodeRowLocks {

    private final EpisodeRepository episodes;

    public EpisodeRowLocks(EpisodeRepository episodes) {
        this.episodes = episodes;
    }

    /**
     * Locks an episode in any status until the caller's transaction ends. This must be the first
     * statement in that transaction that loads the episode, so an existing managed instance cannot
     * hide the previous writer's committed state. Under this lock, read newest/in-flight, insert
     * the asset (or conditionally claim a FAILED retry), then insert the unpublished job. Commit
     * before publishing. Generate the asset id and finish the raw PUT before entering this transaction.
     * An episode's title never changes, so no parent lock is taken. The worker does not acquire this
     * lock. PESSIMISTIC_WRITE uses FOR NO KEY UPDATE on PostgreSQL and FOR UPDATE on H2.
     *
     * @throws EpisodeNotFoundException if the episode does not exist
     * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedEpisode lockForMediaWrite(UUID episodeId) {
        Episode episode = episodes.findForUpdateById(episodeId).orElseThrow(EpisodeNotFoundException::new);
        return new LockedEpisode(episodeId, episode.getTitle().getId());
    }
}
