package com.vodplatform.worker.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TranscodeSettingsTests {
    private static final List<String> NAMES = List.of(TranscodeSettings.FFMPEG_PATH, TranscodeSettings.FFMPEG_TIMEOUT_MIN_SECONDS,
            TranscodeSettings.FFMPEG_TIMEOUT_FACTOR, TranscodeSettings.SEGMENT_SECONDS, TranscodeSettings.TARGET_HEIGHT,
            TranscodeSettings.VIDEO_MAXRATE_KBPS, TranscodeSettings.FFMPEG_THREADS, TranscodeSettings.MAX_STREAMS);
    @TempDir
    Path directory;

    @Test
    void readsTheEightVariablesByTheirDocumentedNames() {
        // Policy values from ADR-010 D27/D28; the two deadline values are only test values (#124 measures them).
        TranscodeSettings settings = TranscodeSettings.from(variables()::get);

        assertThat(settings).isEqualTo(new TranscodeSettings("ffmpeg", 45, new BigDecimal("2.5"), 6, 720, 4000, 1, 8));
        assertThat(NAMES).containsExactly("FFMPEG_PATH", "WORKER_FFMPEG_TIMEOUT_MIN_SECONDS", "WORKER_FFMPEG_TIMEOUT_FACTOR",
                "HLS_SEGMENT_SECONDS", "HLS_TARGET_HEIGHT", "HLS_VIDEO_MAXRATE_KBPS", "WORKER_FFMPEG_THREADS",
                "WORKER_MAX_STREAMS");
    }

    @Test
    void everyVariableIsRequiredBecauseThereAreNoDefaults() {
        for (String name : NAMES) {
            for (String missing : new String[] {null, "", "  "}) {
                Map<String, String> variables = variables();
                variables.put(name, missing);

                assertThatThrownBy(() -> TranscodeSettings.from(variables::get)).as(name)
                        .isInstanceOf(IllegalArgumentException.class).hasMessage(name + " is required");
            }
        }
    }

    @Test
    void malformedNumbersFailNamingTheVariableWithoutEchoingTheValue() {
        for (String name : NAMES.subList(1, NAMES.size())) {
            for (String invalid : List.of("abc", "-1", "+5", " 30", "30 ", "0x10", "1e3", "9999999999", "٣")) {
                Map<String, String> variables = variables();
                variables.put(name, invalid);

                assertThatThrownBy(() -> TranscodeSettings.from(variables::get)).as(name + "=" + invalid)
                        .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith(name)
                        .hasMessageNotContaining(invalid.strip());
            }
        }
        for (String invalid : List.of("1.", ".5", "1,5", "1.2345", "1000", "NaN")) {
            Map<String, String> variables = variables();
            variables.put(TranscodeSettings.FFMPEG_TIMEOUT_FACTOR, invalid);

            assertThatThrownBy(() -> TranscodeSettings.from(variables::get)).as(invalid)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith(TranscodeSettings.FFMPEG_TIMEOUT_FACTOR);
        }
    }

    @Test
    void invalidSettingsAreRejected() {
        assertRejected(TranscodeSettings.FFMPEG_TIMEOUT_MIN_SECONDS, "0", "86401");
        assertRejected(TranscodeSettings.FFMPEG_TIMEOUT_FACTOR, "0", "0.000", "100.001", "101");
        assertRejected(TranscodeSettings.SEGMENT_SECONDS, "0", "1", "11");
        assertRejected(TranscodeSettings.TARGET_HEIGHT, "238", "1082", "721", "0");
        assertRejected(TranscodeSettings.VIDEO_MAXRATE_KBPS, "0", "100001");
        assertRejected(TranscodeSettings.FFMPEG_THREADS, "0", "17");
        assertRejected(TranscodeSettings.MAX_STREAMS, "0", "1001");
        for (String path : List.of("bin/ffmpeg", "./ffmpeg", " ffmpeg", "ffmpeg\n", "ff\u0000mpeg")) {
            Map<String, String> variables = variables();
            variables.put(TranscodeSettings.FFMPEG_PATH, path);

            assertThatThrownBy(() -> TranscodeSettings.from(variables::get)).as(path)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith(TranscodeSettings.FFMPEG_PATH);
        }
        assertThatThrownBy(() -> new TranscodeSettings("ffmpeg", 60, null, 6, 720, 4000, 1, 8))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TranscodeSettings("ffmpeg", 60, new BigDecimal("0.0001"), 6, 720, 4000, 1, 8))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundaryValuesAndAnAbsolutePathAreAccepted() {
        String ffmpeg = directory.resolve("ffmpeg").toString();

        assertThat(new TranscodeSettings(ffmpeg, 1, new BigDecimal("0.001"), 2, 240, 1, 1, 1).boxLongSide()).isEqualTo(426);
        assertThat(new TranscodeSettings(ffmpeg, 86_400, new BigDecimal("100"), 10, 1080, 100_000, 16, 1000).boxLongSide())
                .isEqualTo(1920);
    }

    @ParameterizedTest
    @CsvSource({"720, 1280", "480, 852", "540, 960", "1080, 1920", "240, 426", "360, 640"})
    void theLongSideOfTheBoxIsSixteenNinthsRoundedDownToEven(int targetHeight, int longSide) {
        assertThat(new TranscodeSettings("ffmpeg", 60, BigDecimal.ONE, 6, targetHeight, 4000, 1, 8).boxLongSide()).isEqualTo(longSide);
    }

    @ParameterizedTest
    @CsvSource({"60, 3, 13.000, 60", "60, 3, 20.000, 60", "60, 3, 20.001, 61", "60, 2.5, 10800, 27000",
            "1, 0.001, 13.5, 1", "30, 1.5, 100.000000001, 151"})
    void theDeadlineIsTheMinimumOrTheFactorTimesTheMediaDuration(int minimum, String factor, String mediaSeconds, long expected) {
        TranscodeSettings settings = new TranscodeSettings("ffmpeg", minimum, new BigDecimal(factor), 6, 720, 4000, 1, 8);
        BigDecimal seconds = new BigDecimal(mediaSeconds);
        Duration media = Duration.ofSeconds(seconds.longValue(), seconds.remainder(BigDecimal.ONE).movePointRight(9).longValue());

        assertThat(settings.deadline(media)).isEqualTo(Duration.ofSeconds(expected));
    }

    @Test
    void theDeadlineNeedsAPositiveDuration() {
        TranscodeSettings settings = FfmpegCommandTests.POLICY;

        assertThatThrownBy(() -> settings.deadline(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.deadline(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.deadline(null)).isInstanceOf(NullPointerException.class);
    }

    private static void assertRejected(String name, String... values) {
        for (String value : values) {
            Map<String, String> variables = variables();
            variables.put(name, value);

            assertThatThrownBy(() -> TranscodeSettings.from(variables::get)).as(name + "=" + value)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith(name);
        }
    }

    private static Map<String, String> variables() {
        Map<String, String> variables = new HashMap<>();
        variables.put("FFMPEG_PATH", "ffmpeg");
        variables.put("WORKER_FFMPEG_TIMEOUT_MIN_SECONDS", "45");
        variables.put("WORKER_FFMPEG_TIMEOUT_FACTOR", "2.5");
        variables.put("HLS_SEGMENT_SECONDS", "6");
        variables.put("HLS_TARGET_HEIGHT", "720");
        variables.put("HLS_VIDEO_MAXRATE_KBPS", "4000");
        variables.put("WORKER_FFMPEG_THREADS", "1");
        variables.put("WORKER_MAX_STREAMS", "8");
        return variables;
    }
}
