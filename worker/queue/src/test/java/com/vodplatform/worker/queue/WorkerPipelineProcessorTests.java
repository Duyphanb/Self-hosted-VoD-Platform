package com.vodplatform.worker.queue;

import static com.vodplatform.worker.probe.WorkerFailureCode.INTERNAL_ERROR;
import static com.vodplatform.worker.probe.WorkerFailureCode.STORAGE_ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vodplatform.worker.cleanup.AttemptPrefixCleanup;
import com.vodplatform.worker.cleanup.JobWorkspace;
import com.vodplatform.worker.probe.FfprobeService;
import com.vodplatform.worker.probe.MediaMetadata;
import com.vodplatform.worker.queue.messaging.EncodingJobMessage;
import com.vodplatform.worker.status.WorkerJobStatus;
import com.vodplatform.worker.status.WorkerJobStatus.Attempt;
import com.vodplatform.worker.status.WorkerJobStatus.Claim;
import com.vodplatform.worker.status.WorkerJobStatus.ClaimOutcome;
import com.vodplatform.worker.status.WorkerJobStatus.Failed;
import com.vodplatform.worker.status.WorkerJobStatus.Finish;
import com.vodplatform.worker.status.WorkerJobStatus.FinishOutcome;
import com.vodplatform.worker.status.WorkerJobStatus.HlsObject;
import com.vodplatform.worker.status.WorkerJobStatus.RawObject;
import com.vodplatform.worker.status.WorkerJobStatus.Ready;
import com.vodplatform.worker.storage.HlsUploadService;
import com.vodplatform.worker.storage.HlsUploadService.UploadedHls;
import com.vodplatform.worker.storage.WorkerObjectStorage;
import com.vodplatform.worker.transcode.FfmpegHlsService;
import com.vodplatform.worker.transcode.HlsOutput;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

class WorkerPipelineProcessorTests {
    private final WorkerJobStatus status = mock(WorkerJobStatus.class);
    private final WorkerObjectStorage storage = mock(WorkerObjectStorage.class);
    private final FfprobeService probe = mock(FfprobeService.class);
    private final FfmpegHlsService transcode = mock(FfmpegHlsService.class);
    private final HlsUploadService upload = mock(HlsUploadService.class);
    private final AttemptPrefixCleanup cleanup = mock(AttemptPrefixCleanup.class);
    private final Attempt attempt = new Attempt(UUID.randomUUID(), UUID.randomUUID(), 1);
    private final EncodingJobMessage message = new EncodingJobMessage(attempt.jobId(), attempt.assetId(), 1);
    private final RawObject raw = new RawObject("vod-raw", "fixture.mp4");
    private final MediaMetadata metadata = new MediaMetadata(Duration.ofSeconds(1), 640, 360, 0, false, "h264", null);

    @TempDir
    Path temp;

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void removesRawBeforeUploadAndDoesNotRepeatMediaWhenFinishMustRetry(CapturedOutput output) throws Exception {
        var workspace = new JobWorkspace(temp.toRealPath().resolve("workspace"));
        var hls = mock(HlsOutput.class);
        String key = HlsUploadService.attemptPrefix(attempt.assetId(), 1) + "master.m3u8";
        var location = new HlsObject("vod-hls", key);
        var expected = new Ready(location, metadata);
        when(status.claim(attempt)).thenReturn(new Claim(ClaimOutcome.ACQUIRED, raw));
        when(storage.rawSize(raw.objectKey())).thenReturn(3L);
        when(storage.downloadRaw(eq(raw.objectKey()), any())).thenAnswer(call -> {
            call.<OutputStream>getArgument(1).write(new byte[3]);
            return 3L;
        });
        when(probe.probe(any(), eq("mp4"))).thenReturn(metadata);
        when(transcode.transcode(any(), any(), eq("mp4"), eq(metadata), any())).thenReturn(hls);
        when(upload.upload(eq(attempt.assetId()), eq(1), eq(hls), any())).thenAnswer(call -> {
            try (var children = Files.list(workspace.root())) {
                Path job = children.findFirst().orElseThrow();
                assertThat(Files.notExists(job.resolve("source"))).isTrue();
            }
            return new UploadedHls(location.bucket(), location.masterKey());
        });
        when(status.finish(attempt, expected)).thenThrow(new SQLException("fixture outage"))
                .thenReturn(new Finish(FinishOutcome.COMPLETED, location));

        try (var disk = new WorkerDiskBudget(5000, 1, ignored -> Long.MAX_VALUE)) {
            processor(workspace, disk).process(message);
        }

        verify(transcode).transcode(any(), any(), eq("mp4"), eq(metadata), any());
        verify(upload).upload(eq(attempt.assetId()), eq(1), eq(hls), any());
        verify(status, times(2)).finish(attempt, expected);
        verifyNoInteractions(cleanup);
        assertThat(output.getOut()).contains("Encoding job " + attempt.jobId() + " attempt 1 claimed")
                .contains("Encoding job " + attempt.jobId() + " attempt 1 persisted as READY")
                .doesNotContain(raw.objectKey()).doesNotContain(key);
        try (var children = Files.list(workspace.root())) {
            assertThat(children.count()).isZero();
        }
    }

    @Test
    void refusesToWriteMoreRawBytesThanStatReported() throws Exception {
        var workspace = new JobWorkspace(temp.toRealPath().resolve("workspace"));
        var failed = new Failed(STORAGE_ERROR);
        when(status.claim(attempt)).thenReturn(new Claim(ClaimOutcome.ACQUIRED, raw));
        when(storage.rawSize(raw.objectKey())).thenReturn(3L);
        when(storage.downloadRaw(eq(raw.objectKey()), any())).thenAnswer(call -> {
            call.<OutputStream>getArgument(1).write(new byte[4]);
            return 4L;
        });
        when(status.finish(attempt, failed)).thenReturn(new Finish(FinishOutcome.COMPLETED, null));

        try (var disk = new WorkerDiskBudget(5000, 1, ignored -> Long.MAX_VALUE)) {
            processor(workspace, disk).process(message);
        }

        verifyNoInteractions(probe, transcode, upload);
        verify(status).finish(attempt, failed);
        verify(cleanup).deleteUnreferenced(attempt, null);
        try (var children = Files.list(workspace.root())) {
            assertThat(children.count()).isZero();
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aLostFinishKeepsTheMediaResultButDeletesTheUnreferencedAttemptPrefix(CapturedOutput output) throws Exception {
        var workspace = new JobWorkspace(temp.toRealPath().resolve("workspace"));
        var hls = mock(HlsOutput.class);
        String key = HlsUploadService.attemptPrefix(attempt.assetId(), 1) + "master.m3u8";
        var expected = new Ready(new HlsObject("vod-hls", key), metadata);
        var newer = new HlsObject("vod-hls", HlsUploadService.attemptPrefix(attempt.assetId(), 2) + "master.m3u8");
        downloadsThreeRawBytes();
        when(probe.probe(any(), eq("mp4"))).thenReturn(metadata);
        when(transcode.transcode(any(), any(), eq("mp4"), eq(metadata), any())).thenReturn(hls);
        when(upload.upload(eq(attempt.assetId()), eq(1), eq(hls), any()))
                .thenReturn(new UploadedHls("vod-hls", key));
        when(status.finish(attempt, expected)).thenReturn(new Finish(FinishOutcome.LOST, newer));

        try (var disk = new WorkerDiskBudget(5000, 1, ignored -> Long.MAX_VALUE)) {
            processor(workspace, disk).process(message);
        }

        verify(storage).downloadRaw(eq(raw.objectKey()), any());
        verify(probe).probe(any(), eq("mp4"));
        verify(transcode).transcode(any(), any(), eq("mp4"), eq(metadata), any());
        verify(upload).upload(eq(attempt.assetId()), eq(1), eq(hls), any());
        var order = inOrder(status, cleanup);
        order.verify(status).finish(attempt, expected);
        order.verify(cleanup).deleteUnreferenced(attempt, newer);
        assertWorkspaceEmpty(workspace);
        assertThat(output.getOut()).contains("attempt 1 lost its conditional finish to a newer state")
                .doesNotContain("persisted as READY");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aMissingRawObjectFailsAsStorageErrorBeforeAnyMediaWork(CapturedOutput output) throws Exception {
        var workspace = new JobWorkspace(temp.toRealPath().resolve("workspace"));
        var failed = new Failed(STORAGE_ERROR);
        when(status.claim(attempt)).thenReturn(new Claim(ClaimOutcome.ACQUIRED, raw));
        when(storage.rawSize(raw.objectKey())).thenThrow(new IOException("fixture: no such key"));
        when(status.finish(attempt, failed)).thenReturn(new Finish(FinishOutcome.COMPLETED, null));

        try (var disk = new WorkerDiskBudget(5000, 1, ignored -> Long.MAX_VALUE)) {
            processor(workspace, disk).process(message);
        }

        verify(storage).rawSize(raw.objectKey());
        verify(storage, times(0)).downloadRaw(any(), any());
        verifyNoInteractions(probe, transcode, upload);
        var order = inOrder(status, cleanup);
        order.verify(status).finish(attempt, failed);
        order.verify(cleanup).deleteUnreferenced(attempt, null);
        assertWorkspaceEmpty(workspace);
        assertThat(output.getOut()).contains("attempt 1 persisted as FAILED with STORAGE_ERROR")
                .doesNotContain("fixture: no such key").doesNotContain(raw.objectKey());
    }

    @Test
    void anHlsUploadFailureFailsAsStorageErrorAndDeletesThePrefix() throws Exception {
        var workspace = new JobWorkspace(temp.toRealPath().resolve("workspace"));
        var hls = mock(HlsOutput.class);
        var failed = new Failed(STORAGE_ERROR);
        downloadsThreeRawBytes();
        when(probe.probe(any(), eq("mp4"))).thenReturn(metadata);
        when(transcode.transcode(any(), any(), eq("mp4"), eq(metadata), any())).thenReturn(hls);
        when(upload.upload(eq(attempt.assetId()), eq(1), eq(hls), any()))
                .thenThrow(new IOException("fixture: retries exhausted"));
        when(status.finish(attempt, failed)).thenReturn(new Finish(FinishOutcome.COMPLETED, null));

        try (var disk = new WorkerDiskBudget(5000, 1, ignored -> Long.MAX_VALUE)) {
            processor(workspace, disk).process(message);
        }

        var order = inOrder(status, cleanup);
        order.verify(status).finish(attempt, failed);
        order.verify(cleanup).deleteUnreferenced(attempt, null);
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void anUnexpectedErrorFailsAsInternalErrorWithoutLaterMediaWork() throws Exception {
        var workspace = new JobWorkspace(temp.toRealPath().resolve("workspace"));
        var failed = new Failed(INTERNAL_ERROR);
        downloadsThreeRawBytes();
        when(probe.probe(any(), eq("mp4"))).thenThrow(new IllegalStateException("fixture: unexpected"));
        when(status.finish(attempt, failed)).thenReturn(new Finish(FinishOutcome.COMPLETED, null));

        try (var disk = new WorkerDiskBudget(5000, 1, ignored -> Long.MAX_VALUE)) {
            processor(workspace, disk).process(message);
        }

        verifyNoInteractions(transcode, upload);
        var order = inOrder(status, cleanup);
        order.verify(status).finish(attempt, failed);
        order.verify(cleanup).deleteUnreferenced(attempt, null);
        assertWorkspaceEmpty(workspace);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aStaleDeliveryIsAcknowledgedWithoutMediaWorkAndLogged(CapturedOutput output) throws Exception {
        var workspace = new JobWorkspace(temp.toRealPath().resolve("workspace"));
        when(status.claim(attempt)).thenReturn(new Claim(ClaimOutcome.STALE, null));

        try (var disk = new WorkerDiskBudget(5000, 1, ignored -> Long.MAX_VALUE)) {
            processor(workspace, disk).process(message);
        }

        verifyNoInteractions(storage, probe, transcode, upload, cleanup);
        verify(status, times(0)).finish(any(), any());
        assertThat(output.getOut()).contains("Encoding job " + attempt.jobId()
                + " attempt 1 is finished or superseded; acknowledged without media work");
        assertWorkspaceEmpty(workspace);
    }

    private void downloadsThreeRawBytes() throws Exception {
        when(status.claim(attempt)).thenReturn(new Claim(ClaimOutcome.ACQUIRED, raw));
        when(storage.rawSize(raw.objectKey())).thenReturn(3L);
        when(storage.downloadRaw(eq(raw.objectKey()), any())).thenAnswer(call -> {
            call.<OutputStream>getArgument(1).write(new byte[3]);
            return 3L;
        });
    }

    private static void assertWorkspaceEmpty(JobWorkspace workspace) throws IOException {
        try (var children = Files.list(workspace.root())) {
            assertThat(children.count()).isZero();
        }
    }

    private WorkerPipelineProcessor processor(JobWorkspace workspace, WorkerDiskBudget disk) {
        return new WorkerPipelineProcessor(status, workspace, disk, storage, probe, transcode, upload, cleanup,
                raw.bucket());
    }
}
