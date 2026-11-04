package com.vodplatform.worker.transcode;

import com.vodplatform.worker.probe.ProbeSettings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The FFmpeg settings, validated once, in the {@link ProbeSettings} style. ADR-010 D28: media settings have no
 * application defaults, so a missing or invalid value fails (at worker startup, once Issue 5.7 binds these from
 * the environment). D27/D28 fix the policy values (6, 720, 4000, 1 and the D26 stream cap 8); the two deadline
 * values come from the #124 measurement. The ranges below only reject nonsense; they are not defaults.
 *
 * @param ffmpegPath          {@code FFMPEG_PATH}: a command name resolved through PATH, or an absolute path
 * @param timeoutMinSeconds   {@code WORKER_FFMPEG_TIMEOUT_MIN_SECONDS}, 1-86400
 * @param timeoutFactor       {@code WORKER_FFMPEG_TIMEOUT_FACTOR}, a decimal with at most three fraction digits,
 *                            greater than 0 and at most 100
 * @param segmentSeconds      {@code HLS_SEGMENT_SECONDS}, 2-10 (INFRASTRUCTURE.md)
 * @param targetHeight        {@code HLS_TARGET_HEIGHT}, an even number 240-1080: the short side of the output box
 * @param videoMaxrateKbps    {@code HLS_VIDEO_MAXRATE_KBPS}, 1-100000; the VBV buffer is twice this
 * @param threads             {@code WORKER_FFMPEG_THREADS}, 1-16, passed as {@code -threads} and {@code -filter_threads}
 * @param maxStreams          {@code WORKER_MAX_STREAMS}, 1-1000, the D26 {@code -max_streams} FFprobe also passes
 */
public record TranscodeSettings(String ffmpegPath, int timeoutMinSeconds, BigDecimal timeoutFactor, int segmentSeconds,
                                int targetHeight, int videoMaxrateKbps, int threads, int maxStreams) {
    public static final String FFMPEG_PATH = "FFMPEG_PATH";
    public static final String FFMPEG_TIMEOUT_MIN_SECONDS = "WORKER_FFMPEG_TIMEOUT_MIN_SECONDS";
    public static final String FFMPEG_TIMEOUT_FACTOR = "WORKER_FFMPEG_TIMEOUT_FACTOR";
    public static final String SEGMENT_SECONDS = "HLS_SEGMENT_SECONDS";
    public static final String TARGET_HEIGHT = "HLS_TARGET_HEIGHT";
    public static final String VIDEO_MAXRATE_KBPS = "HLS_VIDEO_MAXRATE_KBPS";
    public static final String FFMPEG_THREADS = "WORKER_FFMPEG_THREADS";
    public static final String MAX_STREAMS = ProbeSettings.MAX_STREAMS;
    private static final Pattern FACTOR = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3})?");
    private static final BigDecimal MAX_FACTOR = BigDecimal.valueOf(100);
    private static final BigDecimal NANOS_PER_SECOND = BigDecimal.valueOf(1_000_000_000L);

    public TranscodeSettings {
        if (!ProbeSettings.validExecutable(ffmpegPath)) {
            throw new IllegalArgumentException(FFMPEG_PATH + " must be a command name or an absolute path");
        }
        requireRange(FFMPEG_TIMEOUT_MIN_SECONDS, timeoutMinSeconds, 1, 86_400);
        Objects.requireNonNull(timeoutFactor, FFMPEG_TIMEOUT_FACTOR);
        if (timeoutFactor.signum() <= 0 || timeoutFactor.compareTo(MAX_FACTOR) > 0 || timeoutFactor.scale() > 3) {
            throw new IllegalArgumentException(FFMPEG_TIMEOUT_FACTOR
                    + " must be greater than 0 and at most 100, with at most three decimals");
        }
        requireRange(SEGMENT_SECONDS, segmentSeconds, 2, 10);
        requireRange(TARGET_HEIGHT, targetHeight, 240, 1080);
        if (targetHeight % 2 != 0) throw new IllegalArgumentException(TARGET_HEIGHT + " must be even");
        requireRange(VIDEO_MAXRATE_KBPS, videoMaxrateKbps, 1, 100_000);
        requireRange(FFMPEG_THREADS, threads, 1, 16);
        requireRange(MAX_STREAMS, maxStreams, 1, 1000);
    }

    /**
     * Reads the eight variables through {@code variables} (for example the worker's Spring {@code Environment}).
     * There is no fallback: a missing, blank or malformed value fails, naming the variable but not echoing it.
     */
    public static TranscodeSettings from(Function<String, String> variables) {
        Objects.requireNonNull(variables, "variables");
        String ffmpegPath = variables.apply(FFMPEG_PATH);
        if (ffmpegPath == null || ffmpegPath.isBlank()) throw new IllegalArgumentException(FFMPEG_PATH + " is required");
        return new TranscodeSettings(ffmpegPath, ProbeSettings.wholeNumber(variables, FFMPEG_TIMEOUT_MIN_SECONDS),
                factor(variables.apply(FFMPEG_TIMEOUT_FACTOR)), ProbeSettings.wholeNumber(variables, SEGMENT_SECONDS),
                ProbeSettings.wholeNumber(variables, TARGET_HEIGHT), ProbeSettings.wholeNumber(variables, VIDEO_MAXRATE_KBPS),
                ProbeSettings.wholeNumber(variables, FFMPEG_THREADS), ProbeSettings.wholeNumber(variables, MAX_STREAMS));
    }

    /**
     * The FFmpeg deadline of ADR-010 D6-D10: {@code max(WORKER_FFMPEG_TIMEOUT_MIN_SECONDS,
     * WORKER_FFMPEG_TIMEOUT_FACTOR * duration)}, the product rounded up to whole seconds.
     *
     * @param mediaDuration the D26 media duration the probe returned
     */
    public Duration deadline(Duration mediaDuration) {
        Objects.requireNonNull(mediaDuration, "mediaDuration");
        if (mediaDuration.isNegative() || mediaDuration.isZero()) {
            throw new IllegalArgumentException("Media duration must be positive");
        }
        BigDecimal seconds = new BigDecimal(mediaDuration.getSeconds())
                .add(BigDecimal.valueOf(mediaDuration.getNano()).divide(NANOS_PER_SECOND));
        long scaled = seconds.multiply(timeoutFactor).setScale(0, RoundingMode.CEILING).longValueExact();
        return Duration.ofSeconds(Math.max(timeoutMinSeconds, scaled));
    }

    /** The long side of the output box: 16/9 of {@code HLS_TARGET_HEIGHT}, rounded down to even (1280 for 720). */
    public int boxLongSide() {
        return targetHeight * 16 / 9 / 2 * 2;
    }

    private static BigDecimal factor(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(FFMPEG_TIMEOUT_FACTOR + " is required");
        if (!FACTOR.matcher(value).matches()) {
            throw new IllegalArgumentException(FFMPEG_TIMEOUT_FACTOR + " must be a plain decimal number");
        }
        return new BigDecimal(value);
    }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
    }
}
