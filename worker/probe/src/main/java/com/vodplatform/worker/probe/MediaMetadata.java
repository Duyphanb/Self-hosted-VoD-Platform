package com.vodplatform.worker.probe;

import java.time.Duration;
import java.util.Objects;

/**
 * What the FFprobe service returns for an accepted raw video (ADR-010 D26). Issue 5.7 persists it in the
 * conditional READY transaction as {@code video_assets.duration_seconds}, {@code width}, {@code height},
 * {@code codec} and {@code bitrate}.
 *
 * @param duration the one media duration: the selected video stream's duration, or the format duration when
 *                 the stream has none. It drives the caps, the FFmpeg deadline, the disk budget and the D27
 *                 output validation, which compares the EXTINF sum with this exact value.
 * @param width    the display width: the selected stream's width, swapped with its height for 90 or 270 degrees
 * @param height   the display height
 * @param rotation the display rotation in whole degrees, normalized to 0, 90, 180 or 270
 * @param rotationFromLegacyTag whether the rotation came from the legacy rotate tag in the absence of a Display Matrix
 * @param codec    the selected video stream's FFprobe codec name, for example {@code h264}
 * @param bitrate  the file's overall bitrate in bit/s from the FFprobe format, or null when FFprobe reports
 *                 none or a value outside the int range
 */
public record MediaMetadata(Duration duration, int width, int height, int rotation, boolean rotationFromLegacyTag,
                            String codec, Integer bitrate) {
    public MediaMetadata {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero() || duration.getSeconds() >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Media duration must be positive and fit whole int seconds");
        }
        if (width < 1 || height < 1) throw new IllegalArgumentException("Media dimensions must be positive");
        if (rotation % 90 != 0 || rotation < 0 || rotation > 270) {
            throw new IllegalArgumentException("Rotation must be 0, 90, 180 or 270 degrees");
        }
        if (codec == null || codec.isBlank() || codec.length() > 100) {
            throw new IllegalArgumentException("Codec must be a name of at most 100 characters");
        }
        if (bitrate != null && bitrate < 1) throw new IllegalArgumentException("Bitrate must be positive when present");
    }

    /** The duration rounded up to whole seconds, as {@code video_assets.duration_seconds} stores it. */
    public int durationSeconds() {
        return Math.toIntExact(duration.getNano() == 0 ? duration.getSeconds() : duration.getSeconds() + 1);
    }
}
