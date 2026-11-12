package com.vodplatform.worker.cleanup;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vodplatform.worker.probe.WorkerFailureCode;
import com.vodplatform.worker.status.WorkerJobStatus;
import com.vodplatform.worker.status.WorkerJobStatus.Attempt;
import com.vodplatform.worker.status.WorkerJobStatus.CommitOutcomeUnknownException;
import com.vodplatform.worker.status.WorkerJobStatus.Failed;
import com.vodplatform.worker.status.WorkerJobStatus.Finish;
import com.vodplatform.worker.status.WorkerJobStatus.FinishOutcome;
import com.vodplatform.worker.status.WorkerJobStatus.HlsObject;
import com.vodplatform.worker.storage.HlsUploadService;
import com.vodplatform.worker.storage.WorkerObjectStorage;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerStartupRecoveryTests {
    private static final Failed INTERRUPTED = new Failed(WorkerFailureCode.WORKER_INTERRUPTED);
    private final Attempt attempt = new Attempt(UUID.randomUUID(), UUID.randomUUID(), 1);
    private final JobWorkspace workspace = mock(JobWorkspace.class);
    private final WorkerJobStatus status = mock(WorkerJobStatus.class);
    private final AttemptPrefixCleanup cleanup = mock(AttemptPrefixCleanup.class);
    private final WorkerStartupRecovery recovery = new WorkerStartupRecovery(workspace, status, cleanup);

    @Test
    void sweepsAndResolvesAnAmbiguousFinishBeforeRequestingCleanup() throws Exception {
        when(status.processingAttempts()).thenReturn(List.of(attempt)).thenReturn(List.of());
        when(status.finish(attempt, INTERRUPTED)).thenThrow(mock(CommitOutcomeUnknownException.class));
        when(status.readFinishAfterUnknownCommit(attempt, INTERRUPTED))
                .thenReturn(new Finish(FinishOutcome.COMPLETED, null));

        recovery.recover();

        var order = inOrder(workspace, status, cleanup);
        order.verify(workspace).sweep();
        order.verify(status).processingAttempts();
        order.verify(status).finish(attempt, INTERRUPTED);
        order.verify(status).readFinishAfterUnknownCommit(attempt, INTERRUPTED);
        order.verify(cleanup).deleteUnreferenced(attempt, null);
        order.verify(status).processingAttempts();
    }

    @Test
    void dbFailureStopsRecoveryAndDoesNotRequestADelete() throws Exception {
        when(status.processingAttempts()).thenReturn(List.of(attempt));
        when(status.finish(attempt, INTERRUPTED)).thenThrow(new SQLException("synthetic private diagnostic"));

        var error = assertThrows(IllegalStateException.class, recovery::recover);
        assertEquals("Worker startup database recovery could not complete", error.getMessage());
        assertNull(error.getCause());
        verify(cleanup, never()).deleteUnreferenced(attempt, null);
    }

    @Test
    void cleanupProtectsReferencedHlsAndRemainsBestEffort() throws Exception {
        var storage = mock(WorkerObjectStorage.class);
        when(storage.hlsBucket()).thenReturn("vod-hls");
        var prefixCleanup = new AttemptPrefixCleanup(storage);
        String prefix = HlsUploadService.attemptPrefix(attempt.assetId(), attempt.number());

        prefixCleanup.deleteUnreferenced(attempt, new HlsObject("vod-hls", prefix + "master.m3u8"));
        verify(storage, never()).deleteHlsAttemptPrefix(prefix);
        doThrow(new IOException("synthetic private diagnostic")).when(storage).deleteHlsAttemptPrefix(prefix);
        assertDoesNotThrow(() -> prefixCleanup.deleteUnreferenced(attempt,
                new HlsObject("vod-hls", HlsUploadService.attemptPrefix(attempt.assetId(), 10) + "master.m3u8")));
        verify(storage).deleteHlsAttemptPrefix(prefix);
    }
}
