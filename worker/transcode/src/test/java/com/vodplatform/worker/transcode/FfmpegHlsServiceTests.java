package com.vodplatform.worker.transcode;

import static com.vodplatform.worker.probe.WorkerFailureCode.ENCODE_FAILED;
import static com.vodplatform.worker.probe.WorkerFailureCode.ENCODE_TIMEOUT;
import static com.vodplatform.worker.probe.WorkerFailureCode.INTERNAL_ERROR;
import static com.vodplatform.worker.probe.WorkerFailureCode.UNSUPPORTED_MEDIA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vodplatform.worker.probe.MediaMetadata;
import com.vodplatform.worker.probe.WorkerFailureCode;
import com.vodplatform.worker.probe.WorkerFailureException;
import com.vodplatform.worker.probe.process.MediaProcessRunner;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Path rules, ordering and the outcome mapping; FfmpegHlsServiceFfmpegTests runs the service with the D25 binary. */
class FfmpegHlsServiceTests {
    private static final MediaMetadata METADATA = new MediaMetadata(Duration.ofSeconds(13), 640, 360, 0, false, "h264", 1000);
    @TempDir
    Path directory;

    @Test
    void anUnknownKeyExtensionFailsBeforeAnythingIsCreatedOrRun() throws IOException {
        Path job = Files.createDirectory(directory.resolve("job-1"));
        Path input = Files.writeString(job.resolve("raw.mp4"), "not media");
        FfmpegHlsService service = service(directory.resolve("no-ffmpeg").toString());

        for (String extension : Arrays.asList("avi", "m3u8", "MP4", "", null)) {
            assertCode(() -> service.transcode(job, input, extension, METADATA), UNSUPPORTED_MEDIA);
        }
        assertThat(job.resolve("hls")).doesNotExist();
        // FFmpeg cannot start here, after the output directories were created.
        assertCode(() -> service.transcode(job, input, "mp4", METADATA), INTERNAL_ERROR);
        assertThat(job.resolve("hls").resolve("segments")).isEmptyDirectory();
    }

    @Test
    void theJobDirectoryMustBeAbsoluteNormalizedWithoutPercentAndReal() throws IOException {
        FfmpegHlsService service = service("ffmpeg");
        Path percent = Files.createDirectory(directory.resolve("job-%05d"));
        Path percentInput = Files.writeString(percent.resolve("raw.mp4"), "x");
        Path job = Files.createDirectory(directory.resolve("job-1"));
        Path input = Files.writeString(job.resolve("raw.mp4"), "x");

        assertRejected(() -> service.transcode(percent, percentInput, "mp4", METADATA));
        assertRejected(() -> service.transcode(Path.of("job-1"), input, "mp4", METADATA));
        assertRejected(() -> service.transcode(job.resolve("..").resolve("job-1"), input, "mp4", METADATA));
        assertRejected(() -> service.transcode(directory.resolve("missing"), input, "mp4", METADATA));
        assertRejected(() -> service.transcode(input, input, "mp4", METADATA));
        assertRejected(() -> service.transcode(null, input, "mp4", METADATA));
        assertThat(percent.resolve("hls")).doesNotExist();
        assertThat(job.resolve("hls")).doesNotExist();
    }

    @Test
    void theInputMustBeARegularFileInsideTheJobDirectory() throws IOException {
        FfmpegHlsService service = service("ffmpeg");
        Path job = Files.createDirectory(directory.resolve("job-1"));
        Path outside = Files.writeString(directory.resolve("raw.mp4"), "x");
        Path folder = Files.createDirectory(job.resolve("raw.mkv"));

        for (Path input : Arrays.asList(null, Path.of("raw.mp4"), outside, job.resolve("missing.mp4"), folder, job,
                job.resolve("sub").resolve("..").resolve("raw.mp4"))) {
            assertRejected(() -> service.transcode(job, input, "mp4", METADATA));
        }
        assertThat(job.resolve("hls")).doesNotExist();
    }

    @Test
    void anExistingHlsDirectoryIsNeverReused() throws IOException {
        Path job = Files.createDirectory(directory.resolve("job-1"));
        Path input = Files.writeString(job.resolve("raw.mp4"), "x");
        Path stale = Files.writeString(Files.createDirectory(job.resolve("hls")).resolve("master.m3u8"), "stale");

        assertCode(() -> service("ffmpeg").transcode(job, input, "mp4", METADATA), INTERNAL_ERROR);
        assertThat(stale).hasContent("stale");
    }

    @Test
    void theDeadlineWinsThenAnyNonZeroExitFails() throws IOException {
        Path hls = validOutput();

        assertCode(() -> FfmpegHlsService.interpret(new MediaProcessRunner.Result(true, -1), hls, METADATA.duration()),
                ENCODE_TIMEOUT);
        for (int exit : new int[] {1, -9, 69, 187, 255}) {
            assertCode(() -> FfmpegHlsService.interpret(new MediaProcessRunner.Result(false, exit), hls, METADATA.duration()),
                    ENCODE_FAILED);
        }
    }

    @Test
    void exitZeroIsNotEnoughWithoutValidOutput() throws IOException {
        Path hls = validOutput();

        HlsOutput output = FfmpegHlsService.interpret(new MediaProcessRunner.Result(false, 0), hls, METADATA.duration());

        assertThat(output.segments()).hasSize(1);
        Files.writeString(hls.resolve("segments").resolve("seg_00001.ts"), "left over");
        assertCode(() -> FfmpegHlsService.interpret(new MediaProcessRunner.Result(false, 0), hls, METADATA.duration()),
                ENCODE_FAILED);
        assertCode(() -> FfmpegHlsService.interpret(new MediaProcessRunner.Result(false, 0), hls, Duration.ofSeconds(30)),
                ENCODE_FAILED);
    }

    private Path validOutput() throws IOException {
        Path hls = Files.createDirectories(directory.resolve("job-1").resolve("hls").resolve("segments")).getParent();
        Files.writeString(hls.resolve("segments").resolve("seg_00000.ts"), "segment");
        Files.writeString(hls.resolve("master.m3u8"),
                "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:13.000000,\nsegments/seg_00000.ts\n#EXT-X-ENDLIST\n");
        return hls;
    }

    private static FfmpegHlsService service(String ffmpeg) {
        return new FfmpegHlsService(new TranscodeSettings(ffmpeg, 60, new BigDecimal("3"), 6, 720, 4000, 1, 8),
                new MediaProcessRunner());
    }

    private static void assertRejected(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertCode(ThrowingCallable call, WorkerFailureCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(WorkerFailureException.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
