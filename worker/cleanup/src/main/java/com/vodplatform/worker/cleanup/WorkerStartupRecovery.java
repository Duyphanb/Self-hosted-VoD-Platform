package com.vodplatform.worker.cleanup;

import com.vodplatform.worker.probe.WorkerFailureCode;
import com.vodplatform.worker.status.WorkerJobStatus;
import com.vodplatform.worker.status.WorkerJobStatus.Attempt;
import com.vodplatform.worker.status.WorkerJobStatus.CommitOutcomeUnknownException;
import com.vodplatform.worker.status.WorkerJobStatus.Failed;
import com.vodplatform.worker.status.WorkerJobStatus.Finish;
import com.vodplatform.worker.status.WorkerJobStatus.FinishOutcome;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Startup recovery requires exactly one worker with no overlapping recreation. */
public final class WorkerStartupRecovery {
    private static final Logger log = LoggerFactory.getLogger(WorkerStartupRecovery.class);
    private static final Failed INTERRUPTED = new Failed(WorkerFailureCode.WORKER_INTERRUPTED);
    private final JobWorkspace workspace;
    private final WorkerJobStatus status;
    private final AttemptPrefixCleanup cleanup;

    public WorkerStartupRecovery(JobWorkspace workspace, WorkerJobStatus status, AttemptPrefixCleanup cleanup) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.status = Objects.requireNonNull(status, "status");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    }

    public void recover() {
        try {
            log.info("Worker temporary recovery removed {} bytes in job files", workspace.sweep());
        } catch (IOException | RuntimeException ignored) {
            throw new IllegalStateException("Worker startup could not sweep the temporary workspace");
        }
        try {
            var attempts = status.processingAttempts();
            for (Attempt attempt : attempts) {
                Finish outcome = interrupt(attempt);
                if (outcome.outcome() != FinishOutcome.COMPLETED) {
                    throw new IllegalStateException("Worker startup could not resolve an orphaned job");
                }
                cleanup.deleteUnreferenced(attempt, outcome.referencedHls());
            }
            if (!status.processingAttempts().isEmpty()) {
                throw new IllegalStateException("Worker startup found unresolved processing jobs");
            }
            log.info("Worker database recovery completed for {} orphaned job pairs", attempts.size());
        } catch (SQLException ignored) {
            throw new IllegalStateException("Worker startup database recovery could not complete");
        }
    }

    private Finish interrupt(Attempt attempt) throws SQLException {
        try {
            return status.finish(attempt, INTERRUPTED);
        } catch (CommitOutcomeUnknownException ignored) {
            Finish observed = status.readFinishAfterUnknownCommit(attempt, INTERRUPTED);
            return observed.outcome() == FinishOutcome.RETRY ? status.finish(attempt, INTERRUPTED) : observed;
        }
    }
}
