package com.vodplatform.worker.probe;

import static com.vodplatform.worker.probe.WorkerFailureCode.INTERNAL_ERROR;
import static com.vodplatform.worker.probe.WorkerFailureCode.MEDIA_LIMIT_EXCEEDED;
import static com.vodplatform.worker.probe.WorkerFailureCode.PROBE_FAILED;
import static com.vodplatform.worker.probe.WorkerFailureCode.PROBE_TIMEOUT;
import static com.vodplatform.worker.probe.WorkerFailureCode.UNSUPPORTED_MEDIA;
import static com.vodplatform.worker.probe.WorkerFailureCode.WORKER_INTERRUPTED;

import com.vodplatform.worker.probe.process.CappedOutput;
import com.vodplatform.worker.probe.process.LineOutput;
import com.vodplatform.worker.probe.process.MediaProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Issue 5.4: probes a downloaded raw video with FFprobe and returns its {@link MediaMetadata}, which Issue 5.7
 * keeps for the conditional finish transaction. The rules are ADR-010 D6-D10 and D26 and SECURITY.md Media
 * Processing Safeguards: the demuxer is forced from the trusted key extension, only the {@code file} protocol
 * and the D26 decoders are allowed, stream count, output and time are bounded, and the D26 selection,
 * rotation, duration and cap rules decide the result. Every failure carries a D28 code.
 */
public final class FfprobeService {
    /** The JSON bound: with the selected entries and at most {@code WORKER_MAX_STREAMS} streams it stays far below. */
    static final int MAX_JSON_BYTES = 64 * 1024;
    /** FFmpeg formats a log line in at most 1 KiB; longer lines are truncated, never buffered whole. */
    static final int MAX_LOG_LINE_BYTES = 4 * 1024;
    private final ProbeSettings settings;
    private final MediaProcessRunner runner;

    public FfprobeService(ProbeSettings settings, MediaProcessRunner runner) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /**
     * @param input        the absolute path of the raw copy in the job directory; a regular file, not a link
     * @param keyExtension the extension of the trusted raw-object key: {@code mp4}, {@code mov}, {@code mkv} or
     *                     {@code webm}; any other value fails before FFprobe runs
     * @throws WorkerFailureException {@code PROBE_FAILED}, {@code PROBE_TIMEOUT}, {@code UNSUPPORTED_MEDIA} or
     *                                {@code MEDIA_LIMIT_EXCEEDED} for the media; {@code INTERNAL_ERROR} when FFprobe
     *                                cannot be started; {@code WORKER_INTERRUPTED} when the calling thread is
     *                                interrupted, after the process tree was killed and with the interrupt status kept
     */
    public MediaMetadata probe(Path input, String keyExtension) {
        TrustedDemuxer demuxer = TrustedDemuxer.forKeyExtension(keyExtension);
        if (input == null || !input.isAbsolute() || !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("FFprobe input must be an absolute path to a regular file");
        }
        CappedOutput json = new CappedOutput(MAX_JSON_BYTES);
        ProbeDiagnostics diagnostics = new ProbeDiagnostics();
        MediaProcessRunner.Result result;
        try {
            result = runner.run(FfprobeCommand.arguments(settings, demuxer, input), settings.timeout(), json,
                    new LineOutput(MAX_LOG_LINE_BYTES, diagnostics));
        } catch (IOException e) {
            throw new WorkerFailureException(INTERNAL_ERROR, "FFprobe could not be started", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerFailureException(WORKER_INTERRUPTED, "FFprobe was interrupted", e);
        }
        return interpret(result, json, diagnostics, settings);
    }

    /** Maps a finished run to metadata or a D28 failure: the deadline first, then FFprobe's own reports. */
    static MediaMetadata interpret(MediaProcessRunner.Result result, CappedOutput json, ProbeDiagnostics diagnostics,
                                   ProbeSettings settings) {
        if (result.timedOut()) throw new WorkerFailureException(PROBE_TIMEOUT, "FFprobe exceeded its deadline");
        // FFmpeg 7.1 reports these two only as text; a nine-track Matroska file even exits 0 after dropping a
        // track, so the probed stream count alone cannot see the cap violation (#194).
        if (diagnostics.streamCapExceeded()) {
            throw new WorkerFailureException(MEDIA_LIMIT_EXCEEDED, "Media has more streams than WORKER_MAX_STREAMS");
        }
        if (diagnostics.decoderNotAllowed()) {
            throw new WorkerFailureException(UNSUPPORTED_MEDIA, "Media needs a decoder outside the allowlist");
        }
        if (result.exitCode() != 0) throw new WorkerFailureException(PROBE_FAILED, "FFprobe could not read the media");
        if (json.overflowed()) throw new WorkerFailureException(PROBE_FAILED, "FFprobe output exceeded its bound");
        return FfprobeOutput.metadata(json.bytes(), settings);
    }
}
