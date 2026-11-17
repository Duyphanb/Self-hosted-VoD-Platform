package com.vodplatform.progress.persistence;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Only the read operation needed by #219; no persistence writes are exposed. */
public interface PlaybackProgressRepository extends Repository<PlaybackProgress, PlaybackProgressId> {
    Optional<PlaybackProgress> findById(PlaybackProgressId id);
}
