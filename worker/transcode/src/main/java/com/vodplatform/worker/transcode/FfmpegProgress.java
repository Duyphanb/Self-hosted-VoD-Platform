package com.vodplatform.worker.transcode;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Reads FFmpeg's {@code -progress pipe:1} output line by line as it is drained, keeping only the last valid
 * {@code out_time_us}: how much media has been encoded. FFmpeg writes {@code N/A} before the first frame and
 * can report a negative time for streams that start below zero; such values, and anything unreadable, are
 * ignored, so the last valid value stays. Nothing else is retained, so memory stays constant.
 */
final class FfmpegProgress implements Consumer<String> {
    private static final String OUT_TIME_US = "out_time_us=";
    /** Up to 18 digits always fits a long. */
    private static final Pattern MICROSECONDS = Pattern.compile("-?[0-9]{1,18}");
    private volatile long outTimeMicros = -1;
    private volatile boolean ended;

    @Override
    public void accept(String line) {
        if (line.startsWith(OUT_TIME_US)) {
            String value = line.substring(OUT_TIME_US.length()).strip();
            if (MICROSECONDS.matcher(value).matches()) {
                long micros = Long.parseLong(value);
                if (micros >= 0) outTimeMicros = micros;
            }
        } else if (line.equals("progress=end")) {
            ended = true;
        }
    }

    /** The last valid {@code out_time_us}, or empty when FFmpeg reported none. */
    Optional<Duration> outTime() {
        long micros = outTimeMicros;
        return micros < 0 ? Optional.empty() : Optional.of(Duration.of(micros, ChronoUnit.MICROS));
    }

    /** Whether FFmpeg wrote its final {@code progress=end} block. */
    boolean ended() {
        return ended;
    }
}
