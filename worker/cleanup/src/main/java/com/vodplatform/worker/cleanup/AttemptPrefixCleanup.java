package com.vodplatform.worker.cleanup;

import com.vodplatform.worker.status.WorkerJobStatus.Attempt;
import com.vodplatform.worker.status.WorkerJobStatus.HlsObject;
import com.vodplatform.worker.storage.HlsUploadService;
import com.vodplatform.worker.storage.WorkerObjectStorage;
import java.io.IOException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Best-effort cleanup after a confirmed terminal write or a genuinely lost finish. */
public final class AttemptPrefixCleanup {
    private static final Logger log = LoggerFactory.getLogger(AttemptPrefixCleanup.class);
    private final WorkerObjectStorage storage;

    public AttemptPrefixCleanup(WorkerObjectStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public void deleteUnreferenced(Attempt attempt, HlsObject referenced) {
        String prefix = HlsUploadService.attemptPrefix(attempt.assetId(), attempt.number());
        if (referenced != null && storage.hlsBucket().equals(referenced.bucket())
                && referenced.masterKey().startsWith(prefix)) return;
        try {
            storage.deleteHlsAttemptPrefix(prefix);
            log.info("HLS attempt prefix delete requested for job {} video asset {} attempt {}", attempt.jobId(),
                    attempt.assetId(), attempt.number());
        } catch (IOException | RuntimeException ignored) {
            log.warn("Could not request HLS attempt prefix deletion for job {} video asset {} attempt {}",
                    attempt.jobId(), attempt.assetId(), attempt.number());
        }
    }
}
