package com.vodplatform.encoding.service;
import com.vodplatform.encoding.messaging.*;
import com.vodplatform.encoding.persistence.EncodingJobRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
public class EncodingRelay {
    private static final Logger log=LoggerFactory.getLogger(EncodingRelay.class);
    private final EncodingJobRepository jobs;
    private final EncodingJobService service;
    private final EncodingJobPublisher publisher;
    private final Clock clock;
    public EncodingRelay(EncodingJobRepository jobs, EncodingJobService service, EncodingJobPublisher publisher, Clock clock) {
        this.jobs=jobs;this.service=service;this.publisher=publisher;this.clock=clock;
    }
    @Scheduled(fixedDelayString="${encoding.relay.interval-seconds}", initialDelayString="${encoding.relay.interval-seconds}",timeUnit=TimeUnit.SECONDS)
    public void relayOnce() {
        EncodingJobService.requireNoTransaction();
        try {
            var cutoff=clock.instant().minus(publisher.confirmTimeout().multipliedBy(2));
            for (var job: jobs.pending(cutoff,PageRequest.of(0,50))) {
                if (!service.publish(new EncodingJobMessage(job.getId(),job.getVideoAssetId(),job.getAttempt()),Optional.empty())) break;
            }
        } catch (RuntimeException failure) { log.warn("encoding relay deferred"); }
    }
}
