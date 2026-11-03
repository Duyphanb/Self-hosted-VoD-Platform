package com.vodplatform.worker.probe;

import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Watches FFprobe's error log, line by line as it is drained, for the two outcomes FFmpeg 7.1 reports only as
 * text. Every line is inspected however much the log is flooded; nothing is retained, so diagnostics never
 * reach a message or log (SECURITY.md). A line can only add a failure, never clear one, so text echoed from a
 * crafted file can at worst change which failure code a rejected file gets.
 */
final class ProbeDiagnostics implements Consumer<String> {
    /**
     * The mov demuxer fails with "Number of streams exceeds max_streams parameter (N)", while matroska logs
     * "Number of tracks exceeds max_streams (N)", drops the extra tracks and still exits 0 (#194).
     */
    static final String STREAM_CAP_EXCEEDED = "exceeds max_streams";
    /** {@code avcodec_open2} logs "Codec (name) not on whitelist 'list'" and FFprobe then exits non-zero. */
    static final Pattern DECODER_NOT_ALLOWED = Pattern.compile("Codec \\([^)]*\\) not on whitelist");
    private volatile boolean streamCapExceeded;
    private volatile boolean decoderNotAllowed;

    @Override
    public void accept(String line) {
        if (line.contains(STREAM_CAP_EXCEEDED)) streamCapExceeded = true;
        if (DECODER_NOT_ALLOWED.matcher(line).find()) decoderNotAllowed = true;
    }

    boolean streamCapExceeded() {
        return streamCapExceeded;
    }

    boolean decoderNotAllowed() {
        return decoderNotAllowed;
    }
}
