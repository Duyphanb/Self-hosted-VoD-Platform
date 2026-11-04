package com.vodplatform.worker.transcode;

import static com.vodplatform.worker.probe.WorkerFailureCode.ENCODE_FAILED;
import static com.vodplatform.worker.probe.WorkerFailureCode.ENCODE_TIMEOUT;
import static com.vodplatform.worker.probe.WorkerFailureCode.INTERNAL_ERROR;
import static com.vodplatform.worker.probe.WorkerFailureCode.WORKER_INTERRUPTED;

import com.vodplatform.worker.probe.MediaMetadata;
import com.vodplatform.worker.probe.TrustedDemuxer;
import com.vodplatform.worker.probe.WorkerFailureException;
import com.vodplatform.worker.probe.process.LineOutput;
import com.vodplatform.worker.probe.process.MediaProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Issue 5.5: turns the probed raw copy into single-quality VOD HLS in the job directory, with the ADR-010 D27
 * profile: H.264 High 8-bit 4:2:0 video, optional AAC-LC stereo audio, MPEG-TS segments under {@code segments/}
 * and one media playlist {@code master.m3u8}. FFmpeg runs under the D6-D10 process rules through the shared
 * {@link MediaProcessRunner} (typed arguments, no shell, closed stdin, PATH/LANG/HOME only, both pipes drained
 * concurrently into bounded sinks, a process-tree deadline), and exit 0 is not success: the
 * {@link HlsOutputValidator} must accept the output too. Every failure carries a D28 code.
 */
public final class FfmpegHlsService {
    /** The output directory inside the job directory; Issue 5.6 uploads its contents. */
    public static final String HLS_DIRECTORY = "hls";
    /** FFmpeg formats a log line in at most 1 KiB; longer lines are truncated, never buffered whole. */
    static final int MAX_LINE_BYTES = 4 * 1024;
    private static final Logger log = LoggerFactory.getLogger(FfmpegHlsService.class);
    private final TranscodeSettings settings;
    private final MediaProcessRunner runner;

    public FfmpegHlsService(TranscodeSettings settings, MediaProcessRunner runner) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /**
     * Creates {@code <jobDirectory>/hls/segments/}, runs FFmpeg into it and validates the result.
     *
     * @param jobDirectory the owned job directory: an absolute, normalized path without {@code %} to an existing
     *                     directory (not a link) that has no {@code hls} entry yet
     * @param input        the raw copy: an absolute regular file (not a link) inside {@code jobDirectory}
     * @param keyExtension the extension of the trusted raw-object key ({@code mp4}, {@code mov}, {@code mkv} or
     *                     {@code webm}); any other value fails as {@code UNSUPPORTED_MEDIA} before FFmpeg runs
     * @param metadata     what the FFprobe service returned for {@code input}
     * @return the validated output, for Issue 5.6 to upload
     * @throws WorkerFailureException {@code ENCODE_FAILED} for a non-zero exit or an output that fails validation;
     *                                {@code ENCODE_TIMEOUT} when the deadline killed the process tree;
     *                                {@code INTERNAL_ERROR} when the output directories cannot be created or FFmpeg
     *                                cannot be started; {@code WORKER_INTERRUPTED} when the calling thread is
     *                                interrupted, after the process tree was killed and with the interrupt status kept
     */
    public HlsOutput transcode(Path jobDirectory, Path input, String keyExtension, MediaMetadata metadata) {
        TrustedDemuxer demuxer = TrustedDemuxer.forKeyExtension(keyExtension);
        Objects.requireNonNull(metadata, "metadata");
        requireJobPaths(jobDirectory, input);
        Path hls = jobDirectory.resolve(HLS_DIRECTORY);
        try {
            Files.createDirectory(hls);
            Files.createDirectory(hls.resolve(FfmpegCommand.SEGMENTS));
        } catch (IOException e) {
            throw new WorkerFailureException(INTERNAL_ERROR, "HLS output directories could not be created", e);
        }
        Duration deadline = settings.deadline(metadata.duration());
        FfmpegProgress progress = new FfmpegProgress();
        AtomicLong errorLines = new AtomicLong();
        long started = System.nanoTime();
        MediaProcessRunner.Result result;
        try {
            result = runner.run(FfmpegCommand.arguments(settings, demuxer, input, hls, metadata), deadline,
                    new LineOutput(MAX_LINE_BYTES, progress), new LineOutput(MAX_LINE_BYTES, line -> errorLines.incrementAndGet()));
        } catch (IOException e) {
            throw new WorkerFailureException(INTERNAL_ERROR, "FFmpeg could not be started", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerFailureException(WORKER_INTERRUPTED, "FFmpeg was interrupted", e);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        // Only counts and times are logged: FFmpeg's own lines can quote paths and media metadata (SECURITY.md).
        String encoded = progress.outTime().map(time -> time.toMillis() + " ms").orElse("no");
        try {
            HlsOutput output = interpret(result, hls, metadata.duration());
            log.info("FFmpeg encoded {} of {} ms of media into {} segment(s) in {} ms", encoded,
                    metadata.duration().toMillis(), output.segments().size(), elapsed.toMillis());
            return output;
        } catch (WorkerFailureException e) {
            log.warn("FFmpeg failed as {} after {} ms (deadline {} s): encoded {} of {} ms of media, {} error line(s)",
                    e.code(), elapsed.toMillis(), deadline.toSeconds(), encoded, metadata.duration().toMillis(),
                    errorLines.get());
            throw e;
        }
    }

    /** Maps a finished run to a validated output or a D28 failure: the deadline first, then the exit, then D27. */
    static HlsOutput interpret(MediaProcessRunner.Result result, Path hlsDirectory, Duration mediaDuration) {
        if (result.timedOut()) throw new WorkerFailureException(ENCODE_TIMEOUT, "FFmpeg exceeded its deadline");
        if (result.exitCode() != 0) throw new WorkerFailureException(ENCODE_FAILED, "FFmpeg exited with an error");
        return HlsOutputValidator.validate(hlsDirectory, mediaDuration);
    }

    private static void requireJobPaths(Path jobDirectory, Path input) {
        if (jobDirectory == null || !jobDirectory.isAbsolute() || !jobDirectory.equals(jobDirectory.normalize())
                || jobDirectory.toString().indexOf('%') >= 0
                || !Files.isDirectory(jobDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("The job directory must be an absolute, normalized path without '%' to a directory");
        }
        if (input == null || !input.isAbsolute() || !input.equals(input.normalize()) || !input.startsWith(jobDirectory)
                || input.equals(jobDirectory) || !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("FFmpeg input must be a regular file inside the job directory");
        }
    }
}
