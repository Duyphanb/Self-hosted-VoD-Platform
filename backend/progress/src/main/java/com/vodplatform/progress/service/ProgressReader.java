package com.vodplatform.progress.service;

import com.vodplatform.progress.persistence.PlaybackProgressId;
import com.vodplatform.progress.persistence.PlaybackProgressRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProgressReader {

    private final PlaybackProgressRepository repository;

    public ProgressReader(PlaybackProgressRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<StoredProgress> find(UUID userId, UUID movieId) {
        return repository.findById(new PlaybackProgressId(userId, movieId))
                .map(row -> new StoredProgress(row.getCurrentSeconds(), row.getDurationSeconds(),
                        row.isFinished(), row.getLastPlayedAt()));
    }
}
