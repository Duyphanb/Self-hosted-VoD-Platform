package com.vodplatform.worker.transcode;

import com.vodplatform.worker.probe.AllowedDecoder;
import com.vodplatform.worker.probe.MediaMetadata;
import com.vodplatform.worker.probe.TrustedDemuxer;
import java.nio.file.Path;
import java.util.List;

/**
 * The FFmpeg argument list of Issue 5.5 (ADR-010 D6, D26 and D27, SECURITY.md Media Processing Safeguards),
 * built only from typed settings and the probed metadata: no shell, no user-controlled fragment.
 *
 * <ul>
 *   <li>Input: {@code -nostdin}, the file-only protocol whitelist, the D26 decoder whitelist and stream cap,
 *       the trusted demuxer and an absolute {@code file:} input.</li>
 *   <li>Orientation: FFmpeg's autorotation stays on, fed with the D26 probed rotation through
 *       {@code -display_rotation}. FFmpeg 7.1 autorotates only from a Display Matrix and ignores the legacy
 *       {@code rotate} tag that D26 accepts as a fallback, so without it a tag-only file would keep its coded
 *       orientation while its metadata says otherwise; with it the output always has the probed orientation.</li>
 *   <li>Geometry: scaled into the box of {@code HLS_TARGET_HEIGHT} (short side) by 16/9 of it (long side) in
 *       the display orientation, never upscaled ({@code min(box, input)}), aspect kept and both sides rounded
 *       down to even.</li>
 *   <li>Colour: the {@code setparams} filter retags every frame BT.709 (metadata only, no tone mapping), because
 *       FFmpeg 7.1 keeps an HDR source's PQ/BT.2020 frame tags when given only the output colour options (the
 *       reproduced finding on #195); the output options are kept as the issue specifies.</li>
 * </ul>
 */
final class FfmpegCommand {
    static final String PLAYLIST = "master.m3u8";
    static final String SEGMENTS = "segments";
    static final String SEGMENT_PATTERN = "seg_%05d.ts";

    private FfmpegCommand() {
    }

    /**
     * @param input        the absolute path of the raw copy
     * @param hlsDirectory the absolute HLS output directory; it must not contain {@code %}, which the segment
     *                     file name pattern would expand
     * @param metadata     the probed metadata: its display size picks the box orientation, its rotation feeds
     *                     the autorotation
     */
    static List<String> arguments(TranscodeSettings settings, TrustedDemuxer demuxer, Path input, Path hlsDirectory,
                                  MediaMetadata metadata) {
        if (!input.isAbsolute()) throw new IllegalArgumentException("FFmpeg input must be an absolute path");
        if (!hlsDirectory.isAbsolute() || hlsDirectory.toString().indexOf('%') >= 0) {
            throw new IllegalArgumentException("The HLS directory must be an absolute path without '%'");
        }
        String threads = String.valueOf(settings.threads());
        return List.of(settings.ffmpegPath(), "-nostdin", "-hide_banner",
                "-xerror", "-nostats", "-loglevel", "error", "-progress", "pipe:1", "-stats_period", "1",
                "-protocol_whitelist", "file",
                "-codec_whitelist", AllowedDecoder.codecWhitelist(),
                "-max_streams", String.valueOf(settings.maxStreams()),
                "-display_rotation:V:0", String.valueOf(metadata.rotation()),
                "-f", demuxer.ffmpegName(),
                "-i", "file:" + input,
                "-map", "0:V:0", "-map", "0:a:0?", "-map_metadata", "-1", "-map_chapters", "-1",
                "-vf", videoFilter(settings, metadata),
                "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p", "-preset", "medium", "-crf", "23",
                "-maxrate:v", settings.videoMaxrateKbps() + "k", "-bufsize:v", 2 * settings.videoMaxrateKbps() + "k",
                "-fpsmax", "60",
                "-force_key_frames", "expr:gte(t,n_forced*" + settings.segmentSeconds() + ")",
                "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709",
                "-c:a", "aac", "-profile:a", "aac_low", "-b:a", "128k", "-ac", "2", "-ar", "48000",
                "-threads", threads, "-filter_threads", threads,
                "-f", "hls", "-hls_time", String.valueOf(settings.segmentSeconds()), "-hls_playlist_type", "vod",
                "-hls_list_size", "0", "-hls_segment_type", "mpegts", "-hls_flags", "independent_segments",
                "-hls_base_url", SEGMENTS + "/",
                "-hls_segment_filename", hlsDirectory.resolve(SEGMENTS).resolve(SEGMENT_PATTERN).toString(),
                hlsDirectory.resolve(PLAYLIST).toString());
    }

    /** Box in display orientation: landscape (and square) 1280x720, portrait 720x1280 for the 720 policy. */
    static String videoFilter(TranscodeSettings settings, MediaMetadata metadata) {
        boolean portrait = metadata.height() > metadata.width();
        int boxWidth = portrait ? settings.targetHeight() : settings.boxLongSide();
        int boxHeight = portrait ? settings.boxLongSide() : settings.targetHeight();
        return "scale=w=min(" + boxWidth + "\\,iw):h=min(" + boxHeight + "\\,ih)"
                + ":force_original_aspect_ratio=decrease:force_divisible_by=2,"
                + "setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709";
    }
}
