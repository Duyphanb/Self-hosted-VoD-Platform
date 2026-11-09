package com.vodplatform.worker.probe;

/**
 * The ADR-010 D6 table: the demuxer both tools are forced to use for a trusted raw-object key extension. The
 * extension comes from the key the backend generated from the validated upload, never from file content or
 * the original file name, so the tools never auto-detect a format (a playlist in particular).
 */
public enum TrustedDemuxer {
    MOV("mov"),
    MATROSKA("matroska");

    private final String ffmpegName;

    TrustedDemuxer(String ffmpegName) {
        this.ffmpegName = ffmpegName;
    }

    /** The FFmpeg {@code -f} value. */
    public String ffmpegName() {
        return ffmpegName;
    }

    /**
     * Maps {@code mp4}/{@code mov} to {@link #MOV} and {@code mkv}/{@code webm} to {@link #MATROSKA}. Any other
     * extension, including another case, fails as {@code UNSUPPORTED_MEDIA} before a tool runs.
     */
    public static TrustedDemuxer forKeyExtension(String extension) {
        return switch (extension == null ? "" : extension) {
            case "mp4", "mov" -> MOV;
            case "mkv", "webm" -> MATROSKA;
            default -> throw new WorkerFailureException(WorkerFailureCode.UNSUPPORTED_MEDIA,
                    "Raw object key extension is not supported");
        };
    }
}
