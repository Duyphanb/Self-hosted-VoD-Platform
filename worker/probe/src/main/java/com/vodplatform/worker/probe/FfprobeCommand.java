package com.vodplatform.worker.probe;

import java.nio.file.Path;
import java.util.List;

/**
 * The FFprobe argument list (SECURITY.md Media Processing Safeguards, ADR-010 D6 and D26), built only from
 * typed settings: no shell, no user-controlled fragment. FFprobe has no {@code -nostdin} option (that is
 * FFmpeg-only); the runner closes its stdin instead.
 */
final class FfprobeCommand {
    /**
     * Only the fields the worker reads, so the JSON stays small for any input and media tags such as titles,
     * comments or locations never leave FFprobe. Side data is read for the Display Matrix rotation.
     */
    static final String SHOW_ENTRIES = "format=duration,bit_rate"
            + ":stream=index,codec_type,codec_name,width,height,duration"
            + ":stream_disposition=attached_pic:stream_tags=rotate:stream_side_data=side_data_type,rotation";

    private FfprobeCommand() {
    }

    /** {@code input} must be absolute; it is passed as a {@code file:} URL under the file-only protocol whitelist. */
    static List<String> arguments(ProbeSettings settings, TrustedDemuxer demuxer, Path input) {
        if (!input.isAbsolute()) throw new IllegalArgumentException("FFprobe input must be an absolute path");
        return List.of(settings.ffprobePath(), "-hide_banner", "-loglevel", "error",
                "-protocol_whitelist", "file",
                "-codec_whitelist", AllowedDecoder.codecWhitelist(),
                "-max_streams", String.valueOf(settings.maxStreams()),
                "-f", demuxer.ffmpegName(),
                "-show_entries", SHOW_ENTRIES,
                "-of", "json",
                "-i", "file:" + input);
    }
}
