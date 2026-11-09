package com.vodplatform.worker.probe;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The FFprobe settings, validated once. ADR-010 D28: media settings have no application defaults, so a
 * missing or invalid value fails (at worker startup, once Issue 5.7 binds these from the environment). The
 * product caps are owner policy in D26 (10800, 1920, 1080, 8); {@code WORKER_FFPROBE_TIMEOUT_SECONDS} is
 * measured by #124. The ranges below only reject nonsense; they are not defaults.
 *
 * @param ffprobePath        {@code FFPROBE_PATH}: a command name resolved through PATH, or an absolute path
 * @param timeoutSeconds     {@code WORKER_FFPROBE_TIMEOUT_SECONDS}, 1-3600
 * @param maxDurationSeconds {@code WORKER_MAX_DURATION_SECONDS}, 1-86400
 * @param maxWidth           {@code WORKER_MAX_WIDTH}, the cap on the long side, 1-16384
 * @param maxHeight          {@code WORKER_MAX_HEIGHT}, the cap on the short side, 1 to {@code WORKER_MAX_WIDTH}
 * @param maxStreams         {@code WORKER_MAX_STREAMS}, 1-1000 (FFmpeg's own default {@code max_streams})
 */
public record ProbeSettings(String ffprobePath, int timeoutSeconds, int maxDurationSeconds, int maxWidth,
                            int maxHeight, int maxStreams) {
    public static final String FFPROBE_PATH = "FFPROBE_PATH";
    public static final String FFPROBE_TIMEOUT_SECONDS = "WORKER_FFPROBE_TIMEOUT_SECONDS";
    public static final String MAX_DURATION_SECONDS = "WORKER_MAX_DURATION_SECONDS";
    public static final String MAX_WIDTH = "WORKER_MAX_WIDTH";
    public static final String MAX_HEIGHT = "WORKER_MAX_HEIGHT";
    public static final String MAX_STREAMS = "WORKER_MAX_STREAMS";
    private static final Pattern WHOLE_NUMBER = Pattern.compile("[0-9]{1,9}");

    public ProbeSettings {
        if (!validExecutable(ffprobePath)) {
            throw new IllegalArgumentException(FFPROBE_PATH + " must be a command name or an absolute path");
        }
        requireRange(FFPROBE_TIMEOUT_SECONDS, timeoutSeconds, 3600);
        requireRange(MAX_DURATION_SECONDS, maxDurationSeconds, 86_400);
        requireRange(MAX_WIDTH, maxWidth, 16_384);
        if (maxHeight < 1 || maxHeight > maxWidth) {
            throw new IllegalArgumentException(MAX_HEIGHT + " caps the short side and must be between 1 and " + MAX_WIDTH);
        }
        requireRange(MAX_STREAMS, maxStreams, 1000);
    }

    /**
     * Reads the six variables through {@code variables} (for example the worker's Spring {@code Environment}).
     * There is no fallback: a missing, blank or non-numeric value fails, naming the variable but not echoing it.
     */
    public static ProbeSettings from(Function<String, String> variables) {
        Objects.requireNonNull(variables, "variables");
        String ffprobePath = variables.apply(FFPROBE_PATH);
        if (ffprobePath == null || ffprobePath.isBlank()) throw new IllegalArgumentException(FFPROBE_PATH + " is required");
        return new ProbeSettings(ffprobePath, wholeNumber(variables, FFPROBE_TIMEOUT_SECONDS),
                wholeNumber(variables, MAX_DURATION_SECONDS), wholeNumber(variables, MAX_WIDTH),
                wholeNumber(variables, MAX_HEIGHT), wholeNumber(variables, MAX_STREAMS));
    }

    public Duration timeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }

    private static int wholeNumber(Function<String, String> variables, String name) {
        String value = variables.apply(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        if (!WHOLE_NUMBER.matcher(value).matches()) throw new IllegalArgumentException(name + " must be a whole number");
        return Integer.parseInt(value);
    }

    private static void requireRange(String name, int value, int max) {
        if (value < 1 || value > max) throw new IllegalArgumentException(name + " must be between 1 and " + max);
    }

    private static boolean validExecutable(String path) {
        if (path == null || path.isBlank() || !path.equals(path.strip())) return false;
        if (path.chars().anyMatch(Character::isISOControl)) return false;
        if (path.indexOf('/') < 0 && path.indexOf('\\') < 0) return true;
        try {
            return Path.of(path).isAbsolute();
        } catch (InvalidPathException e) {
            return false;
        }
    }
}
