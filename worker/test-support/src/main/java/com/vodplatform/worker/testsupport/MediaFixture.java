package com.vodplatform.worker.testsupport;

/**
 * Media generated at test time by {@link MediaFixtures} (#123); no media file is committed. The file name
 * stands in for the trusted object-key extension, and {@link #demuxer()} is the demuxer the ADR-010 D6
 * table forces for that extension. Valid sources are 13 s long, so a 6 s HLS segment target yields two
 * full segments and a short final one.
 */
public enum MediaFixture {
    // Valid sources: the five #123 source types, plus the HDR source that Issue 5.5 converts to 8-bit SDR.
    H264_AAC_MP4("h264-aac.mp4", Kind.VALID),
    VP9_OPUS_WEBM("vp9-opus.webm", Kind.VALID),
    H264_HIGH10_FLAC_MKV("h264-high10-flac.mkv", Kind.VALID),
    PORTRAIT_H264_AAC_MP4("portrait-h264-aac.mp4", Kind.VALID),
    SILENT_H264_MP4("silent-h264.mp4", Kind.VALID),
    HEVC_MAIN10_HDR_AC3_MKV("hevc-main10-hdr-ac3.mkv", Kind.VALID),
    // Rotation (ADR-010 D26), copied from H264_AAC_MP4 with a Display Matrix, or only the legacy rotate tag that
    // the Matroska muxer writes as ROTATE. FFprobe reports the matrix in [-180, 180]: 180 degrees as -180.
    ROTATED_90_MP4("rotated-90.mp4", Kind.VALID),
    ROTATED_MINUS_90_MKV("rotated-minus-90.mkv", Kind.VALID),
    ROTATED_180_MP4("rotated-180.mp4", Kind.VALID),
    ROTATE_TAG_90_MKV("rotate-tag-90.mkv", Kind.VALID),

    // Content that does not match its key: the forced demuxer must refuse it (#103, SECURITY.md).
    EBML_UNDER_MP4_KEY("ebml-under-mp4-key.mp4", Kind.INVALID),
    TEXT_UNDER_MP4_KEY("text-under-mp4-key.mp4", Kind.INVALID),
    HLS_PLAYLIST_UNDER_MP4_KEY("hls-playlist-under-mp4-key.mp4", Kind.INVALID),
    FFCONCAT_UNDER_MKV_KEY("ffconcat-under-mkv-key.mkv", Kind.INVALID),
    // Truncation: moov at the end never probes; a faststart file probes but encodes short.
    TRUNCATED_MOOV_AT_END_MP4("truncated-moov-at-end.mp4", Kind.INVALID),
    TRUNCATED_FASTSTART_MP4("truncated-faststart.mp4", Kind.INVALID),
    // Well-formed files the worker must still reject (ADR-010 D26).
    AUDIO_ONLY_MP4("audio-only.mp4", Kind.INVALID),
    COVER_ART_ONLY_MP4("cover-art-only.mp4", Kind.INVALID),
    MPEG2_VIDEO_MKV("mpeg2-video.mkv", Kind.INVALID),
    MP2_AUDIO_MKV("mp2-audio.mkv", Kind.INVALID),
    ROTATED_45_MP4("rotated-45.mp4", Kind.INVALID),
    NINE_STREAMS_MKV("nine-streams.mkv", Kind.INVALID),
    NINE_STREAMS_MP4("nine-streams.mp4", Kind.INVALID),
    OVER_CAP_2560X1440_MP4("over-cap-2560x1440.mp4", Kind.INVALID);

    private final String fileName;
    private final Kind kind;

    MediaFixture(String fileName, Kind kind) {
        this.fileName = fileName;
        this.kind = kind;
    }

    public String fileName() {
        return fileName;
    }

    public boolean valid() {
        return kind == Kind.VALID;
    }

    public Demuxer demuxer() {
        return Demuxer.forKeyExtension(fileName.substring(fileName.lastIndexOf('.') + 1));
    }

    private enum Kind { VALID, INVALID }

    /** The ADR-010 D6 trusted-extension table. */
    public enum Demuxer {
        MOV("mov"),
        MATROSKA("matroska");

        private final String ffmpegName;

        Demuxer(String ffmpegName) {
            this.ffmpegName = ffmpegName;
        }

        public String ffmpegName() {
            return ffmpegName;
        }

        public static Demuxer forKeyExtension(String extension) {
            return switch (extension) {
                case "mp4", "mov" -> MOV;
                case "mkv", "webm" -> MATROSKA;
                default -> throw new IllegalArgumentException("No trusted demuxer for ." + extension);
            };
        }
    }
}
