package com.vodplatform.worker.queue;

import com.vodplatform.worker.cleanup.AttemptPrefixCleanup;
import com.vodplatform.worker.cleanup.JobWorkspace;
import com.vodplatform.worker.cleanup.WorkerStartupRecovery;
import com.vodplatform.worker.probe.FfprobeService;
import com.vodplatform.worker.probe.ProbeSettings;
import com.vodplatform.worker.probe.process.MediaProcessRunner;
import com.vodplatform.worker.status.WorkerJobStatus;
import com.vodplatform.worker.storage.HlsUploadService;
import com.vodplatform.worker.storage.WorkerObjectStorage;
import com.vodplatform.worker.transcode.FfmpegHlsService;
import com.vodplatform.worker.transcode.TranscodeSettings;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.PlaceholderResolutionException;

@Configuration(proxyBeanMethods = false)
public class WorkerPipelineConfiguration {
    @Bean
    FfprobeService workerProbe(Environment environment) {
        return new FfprobeService(ProbeSettings.from(name -> setting(environment, name)), new MediaProcessRunner());
    }

    @Bean
    FfmpegHlsService workerTranscode(Environment environment) {
        return new FfmpegHlsService(TranscodeSettings.from(name -> setting(environment, name)), new MediaProcessRunner());
    }

    @Bean
    WorkerDiskBudget workerDiskBudget(Environment environment) {
        return new WorkerDiskBudget(
                positive(environment, "WORKER_OUTPUT_BUDGET_KBPS"),
                positive(environment, "WORKER_DISK_RESERVE_MB"));
    }

    @Bean
    JobWorkspace workerJobWorkspace(Environment environment) {
        String root = required(environment, "WORKER_TEMP_DIR");
        try {
            return new JobWorkspace(Path.of(root));
        } catch (IOException | InvalidPathException ignored) {
            throw new IllegalStateException("WORKER_TEMP_DIR could not be initialized safely");
        }
    }

    @Bean
    AttemptPrefixCleanup workerAttemptCleanup(WorkerObjectStorage storage) {
        return new AttemptPrefixCleanup(storage);
    }

    @Bean
    WorkerStartupRecovery workerStartupRecovery(JobWorkspace workspace, WorkerJobStatus status,
                                                 AttemptPrefixCleanup cleanup) {
        return new WorkerStartupRecovery(workspace, status, cleanup);
    }

    @Bean
    WorkerPipelineProcessor encodingJobProcessor(WorkerJobStatus status, JobWorkspace workspace,
            WorkerDiskBudget disk, WorkerObjectStorage storage, FfprobeService probe, FfmpegHlsService transcode,
            HlsUploadService upload, AttemptPrefixCleanup cleanup, Environment environment) {
        return new WorkerPipelineProcessor(status, workspace, disk, storage, probe, transcode, upload, cleanup,
                required(environment, "MINIO_BUCKET_RAW"));
    }

    private static int positive(Environment environment, String name) {
        int value = ProbeSettings.wholeNumber(key -> setting(environment, key), name);
        if (value == 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static String required(Environment environment, String name) {
        String value = setting(environment, name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static String setting(Environment environment, String name) {
        try {
            return environment.getProperty(name);
        } catch (PlaceholderResolutionException ignored) {
            throw new IllegalArgumentException(name + " could not be resolved");
        }
    }
}
