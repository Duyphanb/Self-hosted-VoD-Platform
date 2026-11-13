package com.vodplatform.worker.queue;

import static com.vodplatform.worker.probe.WorkerFailureCode.INTERNAL_ERROR;
import static com.vodplatform.worker.probe.WorkerFailureCode.STORAGE_ERROR;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import com.vodplatform.worker.cleanup.AttemptPrefixCleanup;
import com.vodplatform.worker.cleanup.JobWorkspace;
import com.vodplatform.worker.probe.FfprobeService;
import com.vodplatform.worker.probe.WorkerFailureException;
import com.vodplatform.worker.queue.messaging.EncodingJobMessage;
import com.vodplatform.worker.queue.messaging.EncodingJobProcessor;
import com.vodplatform.worker.status.WorkerJobStatus;
import com.vodplatform.worker.status.WorkerJobStatus.Attempt;
import com.vodplatform.worker.status.WorkerJobStatus.CommitOutcomeUnknownException;
import com.vodplatform.worker.status.WorkerJobStatus.Completion;
import com.vodplatform.worker.status.WorkerJobStatus.Failed;
import com.vodplatform.worker.status.WorkerJobStatus.Finish;
import com.vodplatform.worker.status.WorkerJobStatus.FinishOutcome;
import com.vodplatform.worker.status.WorkerJobStatus.HlsObject;
import com.vodplatform.worker.status.WorkerJobStatus.RawObject;
import com.vodplatform.worker.status.WorkerJobStatus.Ready;
import com.vodplatform.worker.storage.HlsUploadService;
import com.vodplatform.worker.storage.WorkerObjectStorage;
import com.vodplatform.worker.transcode.FfmpegHlsService;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

/** Processes one delivery. A normal return means the broker can acknowledge it. */
final class WorkerPipelineProcessor implements EncodingJobProcessor {
    private static final Logger log = LoggerFactory.getLogger(WorkerPipelineProcessor.class);
    private final WorkerJobStatus status;
    private final JobWorkspace workspace;
    private final WorkerDiskBudget disk;
    private final WorkerObjectStorage storage;
    private final FfprobeService probe;
    private final FfmpegHlsService transcode;
    private final HlsUploadService upload;
    private final AttemptPrefixCleanup cleanup;
    private final String rawBucket;
    private volatile boolean stopping;

    WorkerPipelineProcessor(WorkerJobStatus status, JobWorkspace workspace, WorkerDiskBudget disk,
            WorkerObjectStorage storage, FfprobeService probe, FfmpegHlsService transcode,
            HlsUploadService upload, AttemptPrefixCleanup cleanup, String rawBucket) {
        this.status = status;
        this.workspace = workspace;
        this.disk = disk;
        this.storage = storage;
        this.probe = probe;
        this.transcode = transcode;
        this.upload = upload;
        this.cleanup = cleanup;
        this.rawBucket = rawBucket;
    }

    @Override
    public void process(EncodingJobMessage message) {
        var attempt = new Attempt(message.encodingJobId(), message.videoAssetId(), message.attempt());
        RawObject raw = claim(attempt);
        if (raw == null) return;

        Completion completion = processMedia(attempt, raw);
        Finish finished = finish(attempt, completion);
        if (finished.outcome() == FinishOutcome.LOST) {
            log.warn("Encoding job {} attempt {} lost its conditional finish to a newer state",
                    attempt.jobId(), attempt.number());
        } else if (completion instanceof Failed failed) {
            log.info("Encoding job {} attempt {} persisted as FAILED with {}", attempt.jobId(), attempt.number(),
                    failed.code());
        } else {
            log.info("Encoding job {} attempt {} persisted as READY", attempt.jobId(), attempt.number());
        }
        if (completion instanceof Failed || finished.outcome() == FinishOutcome.LOST) {
            cleanup.deleteUnreferenced(attempt, finished.referencedHls());
        }
    }

    private RawObject claim(Attempt attempt) {
        var backoff = new DatabaseBackoff("claim", attempt);
        boolean readback = false;
        while (true) {
            requireRunning();
            try {
                var claim = readback ? status.readClaimAfterUnknownCommit(attempt) : status.claim(attempt);
                switch (claim.outcome()) {
                    case ACQUIRED -> {
                        log.info("Encoding job {} attempt {} claimed", attempt.jobId(), attempt.number());
                        return claim.raw();
                    }
                    case STALE -> {
                        log.info("Encoding job {} attempt {} is finished or superseded; acknowledged without "
                                + "media work", attempt.jobId(), attempt.number());
                        return null;
                    }
                    case INVALID -> throw new AmqpRejectAndDontRequeueException("Encoding job identifiers do not match");
                    case RETRY -> readback = false;
                    case UNRESOLVED -> { /* Hold the delivery while the DB outcome is unknown. */ }
                }
            } catch (CommitOutcomeUnknownException ignored) {
                readback = true;
            } catch (SQLException ignored) {
                // Preserve the phase, particularly readback after an uncertain COMMIT.
            }
            backoff.pause();
        }
    }

    private Finish finish(Attempt attempt, Completion completion) {
        var backoff = new DatabaseBackoff("finish", attempt);
        boolean readback = false;
        while (true) {
            requireRunning();
            try {
                Finish finish = readback ? status.readFinishAfterUnknownCommit(attempt, completion)
                        : status.finish(attempt, completion);
                switch (finish.outcome()) {
                    case COMPLETED, LOST -> { return finish; }
                    case RETRY -> readback = false;
                    case UNRESOLVED -> { /* Keep this completion in memory and hold the delivery. */ }
                }
            } catch (CommitOutcomeUnknownException ignored) {
                readback = true;
            } catch (SQLException ignored) {
                // Retry this database phase without repeating any media work.
            }
            backoff.pause();
        }
    }

    private Completion processMedia(Attempt attempt, RawObject raw) {
        Path job = null;
        try {
            requireRunning();
            job = workspace.createJobDirectory();
            long rawBytes = rawSize(raw);
            disk.beforeDownload(job, rawBytes);
            Path input = job.resolve("source");
            download(raw, input, rawBytes);
            requireRunning();
            int dot = raw.objectKey().lastIndexOf('.');
            String extension = dot < 0 ? "" : raw.objectKey().substring(dot + 1);
            var metadata = probe.probe(input, extension);
            disk.afterProbe(job, rawBytes, metadata.duration());

            var watch = disk.watch(job, upload::cancelActiveUpload);
            HlsUploadService.UploadedHls result;
            try (watch) {
                watch.check();
                BooleanSupplier cancelled = () -> stopping || watch.failed();
                var hls = transcode.transcode(job, input, extension, metadata, cancelled);
                Files.delete(input);
                requireRunning();
                watch.check();
                try {
                    result = upload.upload(attempt.assetId(), attempt.number(), hls, cancelled);
                } catch (IOException ignored) {
                    throw new WorkerFailureException(STORAGE_ERROR, "Worker HLS upload failed");
                }
            } catch (RuntimeException failure) {
                watch.check();
                throw failure;
            }
            watch.check();
            requireRunning();
            return new Ready(new HlsObject(result.bucket(), result.masterObjectKey()), metadata);
        } catch (WorkerFailureException failure) {
            requireRunning();
            log.warn("Encoding job {} failed as {}", attempt.jobId(), failure.code());
            return new Failed(failure.code());
        } catch (IOException | RuntimeException ignored) {
            requireRunning();
            log.warn("Encoding job {} failed as {}", attempt.jobId(), INTERNAL_ERROR);
            return new Failed(INTERNAL_ERROR);
        } finally {
            if (job != null) {
                try {
                    workspace.removeJobDirectory(job);
                } catch (IOException ignored) {
                    log.warn("Could not remove the local workspace for encoding job {}", attempt.jobId());
                }
            }
        }
    }

    private long rawSize(RawObject raw) {
        if (!rawBucket.equals(raw.bucket())) throw new WorkerFailureException(STORAGE_ERROR, "Unexpected raw bucket");
        try {
            long size = storage.rawSize(raw.objectKey());
            if (size > 0) return size;
        } catch (IOException ignored) {
            // The object service hides all remote details.
        }
        throw new WorkerFailureException(STORAGE_ERROR, "Raw object metadata is unavailable");
    }

    private void download(RawObject raw, Path input, long size) {
        try (var output = new BoundedDownload(Files.newOutputStream(input, CREATE_NEW, WRITE, NOFOLLOW_LINKS), size)) {
            if (storage.downloadRaw(raw.objectKey(), output) != size || output.remaining != 0) {
                throw new IOException("Raw object length changed during download");
            }
        } catch (IOException ignored) {
            throw new WorkerFailureException(STORAGE_ERROR, "Worker raw download failed");
        }
    }

    private void requireRunning() {
        if (stopping || Thread.currentThread().isInterrupted()) {
            throw new ImmediateRequeueAmqpException("Worker stopped before terminal persistence");
        }
    }

    @EventListener(ContextClosedEvent.class)
    void stop() {
        stopping = true;
        upload.cancelActiveUpload();
    }

    private final class DatabaseBackoff {
        private final String phase;
        private final Attempt attempt;
        private long millis = 100;

        private DatabaseBackoff(String phase, Attempt attempt) {
            this.phase = phase;
            this.attempt = attempt;
        }

        private void pause() {
            if (millis == 100) log.warn("Encoding job {} is waiting for database {}", attempt.jobId(), phase);
            requireRunning();
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                requireRunning();
            }
            millis = Math.min(millis * 2, 5_000);
        }
    }

    private static final class BoundedDownload extends FilterOutputStream {
        private long remaining;

        private BoundedDownload(OutputStream output, long size) {
            super(output);
            remaining = size;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            requireCapacity(length);
            out.write(bytes, offset, length);
            remaining -= length;
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            out.write(value);
            remaining--;
        }

        private void requireCapacity(int length) throws IOException {
            if (length > remaining) throw new IOException("Raw object exceeded its verified length");
        }
    }
}
