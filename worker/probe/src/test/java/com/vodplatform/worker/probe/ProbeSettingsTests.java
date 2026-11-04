package com.vodplatform.worker.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProbeSettingsTests {
    private static final List<String> NAMES = List.of(ProbeSettings.FFPROBE_PATH, ProbeSettings.FFPROBE_TIMEOUT_SECONDS,
            ProbeSettings.MAX_DURATION_SECONDS, ProbeSettings.MAX_WIDTH, ProbeSettings.MAX_HEIGHT, ProbeSettings.MAX_STREAMS);
    @TempDir
    Path directory;

    @Test
    void readsTheSixVariablesByTheirAdr010Names() {
        // The caps are the ADR-010 D26 policy values; the timeout is only a test value (#124 measures it).
        ProbeSettings settings = ProbeSettings.from(variables()::get);

        assertThat(settings).isEqualTo(new ProbeSettings("ffprobe", 7, 10_800, 1920, 1080, 8));
        assertThat(settings.timeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(NAMES).containsExactly("FFPROBE_PATH", "WORKER_FFPROBE_TIMEOUT_SECONDS", "WORKER_MAX_DURATION_SECONDS",
                "WORKER_MAX_WIDTH", "WORKER_MAX_HEIGHT", "WORKER_MAX_STREAMS");
    }

    @Test
    void everyVariableIsRequiredBecauseThereAreNoDefaults() {
        for (String name : NAMES) {
            for (String missing : new String[] {null, "", "  "}) {
                Map<String, String> variables = variables();
                variables.put(name, missing);

                assertThatThrownBy(() -> ProbeSettings.from(variables::get)).as(name)
                        .isInstanceOf(IllegalArgumentException.class).hasMessage(name + " is required");
            }
        }
    }

    @Test
    void numbersMustBePlainWholeNumbersAndAreNotEchoed() {
        for (String name : NAMES.subList(1, NAMES.size())) {
            for (String invalid : List.of("abc", "-1", "+5", "1.5", " 30", "30 ", "0x10", "1e3", "9999999999", "٣")) {
                Map<String, String> variables = variables();
                variables.put(name, invalid);

                assertThatThrownBy(() -> ProbeSettings.from(variables::get)).as(name + "=" + invalid)
                        .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith(name)
                        .hasMessageNotContaining(invalid.strip());
            }
        }
    }

    @Test
    void valuesOutsideTheirRangeFailNamingTheVariable() {
        assertRejected(ProbeSettings.FFPROBE_TIMEOUT_SECONDS, "0", "3601");
        assertRejected(ProbeSettings.MAX_DURATION_SECONDS, "0", "86401");
        assertRejected(ProbeSettings.MAX_WIDTH, "0", "16385");
        assertRejected(ProbeSettings.MAX_STREAMS, "0", "1001");
        // The short-side cap may not exceed the long-side cap.
        assertRejected(ProbeSettings.MAX_HEIGHT, "0", "1921");
        assertThat(new ProbeSettings("ffprobe", 3600, 86_400, 16_384, 16_384, 1000).maxHeight()).isEqualTo(16_384);
        assertThat(new ProbeSettings("ffprobe", 1, 1, 1, 1, 1).maxStreams()).isOne();
    }

    @Test
    void ffprobePathIsACommandNameOrAnAbsolutePath() {
        assertThat(new ProbeSettings("ffprobe", 1, 1, 1, 1, 1).ffprobePath()).isEqualTo("ffprobe");
        String absolute = directory.resolve("ffprobe").toString();
        assertThat(new ProbeSettings(absolute, 1, 1, 1, 1, 1).ffprobePath()).isEqualTo(absolute);
        for (String invalid : List.of("bin/ffprobe", "./ffprobe", "..\\ffprobe", " ffprobe", "ffprobe\n", "ff\u0000probe", "")) {
            assertThatThrownBy(() -> new ProbeSettings(invalid, 1, 1, 1, 1, 1)).as(invalid)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith(ProbeSettings.FFPROBE_PATH);
        }
        assertThatThrownBy(() -> new ProbeSettings(null, 1, 1, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertRejected(String name, String... values) {
        for (String value : values) {
            Map<String, String> variables = variables();
            variables.put(name, value);

            assertThatThrownBy(() -> ProbeSettings.from(variables::get)).as(name + "=" + value)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith(name);
        }
    }

    private static Map<String, String> variables() {
        Map<String, String> variables = new HashMap<>();
        variables.put("FFPROBE_PATH", "ffprobe");
        variables.put("WORKER_FFPROBE_TIMEOUT_SECONDS", "7");
        variables.put("WORKER_MAX_DURATION_SECONDS", "10800");
        variables.put("WORKER_MAX_WIDTH", "1920");
        variables.put("WORKER_MAX_HEIGHT", "1080");
        variables.put("WORKER_MAX_STREAMS", "8");
        return variables;
    }
}
