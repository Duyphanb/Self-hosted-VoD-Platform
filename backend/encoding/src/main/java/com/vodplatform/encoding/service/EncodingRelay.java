package com.vodplatform.encoding.service;

import com.vodplatform.encoding.messaging.EncodingJobMessage;
import com.vodplatform.encoding.messaging.EncodingJobPublisher;
import com.vodplatform.encoding.persistence.EncodingJobRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class EncodingRelay {
    private static final Logger log = LoggerFactory.getLogger(EncodingRelay.class);

    private final EncodingJobRepository jobs;

    private final EncodingJobService service;

    private final EncodingJobPublisher publisher;

    private final Clock clock;

    public EncodingRelay(
            EncodingJobRepository jobs,
            EncodingJobService service,
            EncodingJobPublisher publisher,
            Clock clock) {
        this.jobs = jobs;
        this.service = service;
        this.publisher = publisher;
        this.clock = clock;
    }

    public void relayOnce() {
        EncodingJobService.requireNoTransaction();
        try {
            var cutoff = clock.instant().minus(publisher.confirmTimeout().multipliedBy(2));
            for (var job : jobs.pending(cutoff, PageRequest.of(0, 50))) {
                if (!service.republish(
                        new EncodingJobMessage(
                                job.getId(), job.getVideoAssetId(), job.getAttempt()))) {
                    break;
                }
            }
        } catch (RuntimeException failure) {
            log.warn(
                    "encoding relay deferred exceptionClass={}",
                    failure.getClass().getSimpleName());
        }
    }
}
