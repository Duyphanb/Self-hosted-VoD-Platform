package com.vodplatform.encoding.service;

import com.vodplatform.catalog.service.MovieRowLocks;
import com.vodplatform.encoding.persistence.*;
import com.vodplatform.encoding.messaging.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Transaction boundary reused by upload and later retry. Never hold a DB transaction during send. */
@Service
public class EncodingJobService {
    private static final Logger log = LoggerFactory.getLogger(EncodingJobService.class);
    private final VideoAssetRepository assets;
    private final EncodingJobRepository jobs;
    private final MovieRowLocks locks;
    private final EncodingJobPublisher publisher;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public EncodingJobService(VideoAssetRepository assets, EncodingJobRepository jobs, MovieRowLocks locks,
            EncodingJobPublisher publisher, Clock clock, PlatformTransactionManager manager) {
        this.assets=assets; this.jobs=jobs; this.locks=locks; this.publisher=publisher; this.clock=clock;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(20);
    }
    public static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Upload and publication require no ambient transaction");
    }
    public void precheck(UUID movieId) {
        requireNoTransaction();
        try {
            transaction.executeWithoutResult(status -> {
                locks.lockForMediaWrite(movieId);
                if (assets.existsInFlightByMovieId(movieId)) throw new VideoUploadInProgressException();
            });
        } catch (com.vodplatform.shared.error.ApiException business) { throw business; }
        catch (RuntimeException failure) { throw new UploadDatabaseException(); }
    }
    public void commitNewUpload(UUID movieId, UUID assetId, UUID jobId, String bucket, String key) {
        requireNoTransaction();
        boolean[] bodyCompleted={false};
        try {
            transaction.executeWithoutResult(status -> {
                var lock=locks.lockForMediaWrite(movieId);
                if (assets.existsInFlightByMovieId(movieId)) throw new VideoUploadInProgressException();
                var asset=assets.save(VideoAsset.queued(lock, assetId, bucket, key, clock));
                jobs.saveAndFlush(EncodingJob.firstAttempt(asset, jobId));
                bodyCompleted[0]=true;
            });
        } catch (RuntimeException failure) {
            if (bodyCompleted[0]) {
                // Acquiring the same movie lock waits for an in-doubt original writer. Read in
                // a fresh READ COMMITTED transaction, never in the aborted transaction.
                try {
                    Boolean committed=transaction.execute(status -> {
                        locks.lockForMediaWrite(movieId);
                        boolean asset=assets.existsById(assetId), job=jobs.existsById(jobId);
                        if (asset != job) throw new UploadCommitUnknownException();
                        return asset;
                    });
                    if (Boolean.TRUE.equals(committed)) return;
                } catch (RuntimeException unknown) {
                    log.warn("upload commit unknown movieId={} videoAssetId={} encodingJobId={}",movieId,assetId,jobId);
                    throw new UploadCommitUnknownException();
                }
                throw new UploadDatabaseException();
            }
            if (EncodingConstraints.isViolationOf(failure, EncodingConstraints.VIDEO_ASSETS_MOVIE_IN_FLIGHT))
                throw new VideoUploadInProgressException();
            if (failure instanceof com.vodplatform.shared.error.ApiException business) throw business;
            throw new UploadDatabaseException();
        }
    }
    public boolean publish(EncodingJobMessage message, Optional<String> requestId) {
        requireNoTransaction();
        try {
            if (!(publisher.publish(message,requestId) instanceof PublishOutcome.Confirmed)) return false;
            transaction.executeWithoutResult(status -> {
                int count=jobs.markPublished(message.encodingJobId(),clock.instant().truncatedTo(ChronoUnit.MICROS));
                if (count==0) log.debug("publication already recorded encodingJobId={}",message.encodingJobId());
            });
            return true;
        } catch (RuntimeException failure) {
            log.warn("publication pending encodingJobId={} videoAssetId={}",message.encodingJobId(),message.videoAssetId());
            return false;
        }
    }
}
