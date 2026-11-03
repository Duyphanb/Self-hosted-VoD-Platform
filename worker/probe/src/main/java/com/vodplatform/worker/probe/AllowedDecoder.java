package com.vodplatform.worker.probe;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The ADR-010 D26 decoder allowlist, fixed in code. FFprobe and FFmpeg both pass {@link #codecWhitelist()} as
 * {@code -codec_whitelist}, so neither tool opens any other decoder, including while probing; a stream that
 * needs one makes the tool fail and the worker report {@code UNSUPPORTED_MEDIA}. It does not restrict
 * demuxers or parsers.
 *
 * <p>Each entry pairs the codec name FFprobe reports ({@code codec_name}) with the decoder implementation the
 * ADR-010 D25 binary selects for it, as {@code ffmpeg -decoders} names it. Where a codec has several
 * decoders FFmpeg takes the first registered one, which is why SubRip is {@code srt}, ASS is {@code ssa},
 * MP3 is {@code mp3float} and AV1 is {@code libdav1d}. {@code DecoderAllowlistFfmpegTests} pins every name
 * against the binary: each family probes with this list and fails without its own entry.
 */
public enum AllowedDecoder {
    H264(MediaType.VIDEO, "h264", "h264"),
    HEVC(MediaType.VIDEO, "hevc", "hevc"),
    VP8(MediaType.VIDEO, "vp8", "vp8"),
    VP9(MediaType.VIDEO, "vp9", "vp9"),
    AV1(MediaType.VIDEO, "av1", "libdav1d"),
    MPEG4_PART2(MediaType.VIDEO, "mpeg4", "mpeg4"),
    AAC(MediaType.AUDIO, "aac", "aac"),
    MP3(MediaType.AUDIO, "mp3", "mp3float"),
    OPUS(MediaType.AUDIO, "opus", "opus"),
    VORBIS(MediaType.AUDIO, "vorbis", "vorbis"),
    AC3(MediaType.AUDIO, "ac3", "ac3"),
    EAC3(MediaType.AUDIO, "eac3", "eac3"),
    FLAC(MediaType.AUDIO, "flac", "flac"),
    ALAC(MediaType.AUDIO, "alac", "alac"),
    PCM_S16LE(MediaType.AUDIO, "pcm_s16le", "pcm_s16le"),
    PCM_S16BE(MediaType.AUDIO, "pcm_s16be", "pcm_s16be"),
    PCM_S24LE(MediaType.AUDIO, "pcm_s24le", "pcm_s24le"),
    PCM_S24BE(MediaType.AUDIO, "pcm_s24be", "pcm_s24be"),
    MOV_TEXT(MediaType.SUBTITLE, "mov_text", "mov_text"),
    SUBRIP(MediaType.SUBTITLE, "subrip", "srt"),
    ASS(MediaType.SUBTITLE, "ass", "ssa"),
    WEBVTT(MediaType.SUBTITLE, "webvtt", "webvtt");

    private static final String WHITELIST = Arrays.stream(values()).map(AllowedDecoder::decoder)
            .collect(Collectors.joining(","));
    private final MediaType type;
    private final String codec;
    private final String decoder;

    AllowedDecoder(MediaType type, String codec, String decoder) {
        this.type = type;
        this.codec = codec;
        this.decoder = decoder;
    }

    public MediaType type() {
        return type;
    }

    /** The codec name FFprobe reports for such a stream. */
    public String codec() {
        return codec;
    }

    /** The decoder implementation name listed in {@code -codec_whitelist}. */
    public String decoder() {
        return decoder;
    }

    /** The {@code -codec_whitelist} value both tools pass. */
    public static String codecWhitelist() {
        return WHITELIST;
    }

    /** Whether an FFprobe stream of {@code codecType} with {@code codecName} belongs to an allowed family. */
    public static boolean allows(String codecType, String codecName) {
        for (AllowedDecoder allowed : values()) {
            if (allowed.type.ffprobeName.equals(codecType) && allowed.codec.equals(codecName)) return true;
        }
        return false;
    }

    /** The FFprobe {@code codec_type} values whose streams are decoded and so must use an allowed decoder. */
    public enum MediaType {
        VIDEO("video"),
        AUDIO("audio"),
        SUBTITLE("subtitle");

        private final String ffprobeName;

        MediaType(String ffprobeName) {
            this.ffprobeName = ffprobeName;
        }

        public String ffprobeName() {
            return ffprobeName;
        }

        public static boolean isDecoded(String codecType) {
            for (MediaType type : values()) {
                if (type.ffprobeName.equals(codecType)) return true;
            }
            return false;
        }
    }
}
