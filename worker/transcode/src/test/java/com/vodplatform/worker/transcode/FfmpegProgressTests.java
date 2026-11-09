package com.vodplatform.worker.transcode;

import static org.assertj.core.api.Assertions.assertThat;

import com.vodplatform.worker.probe.process.LineOutput;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FfmpegProgressTests {

    @Test
    void keepsOnlyTheLastOutTimeOfFfmpegProgressBlocks() {
        FfmpegProgress progress = new FfmpegProgress();
        // FFmpeg 7.1 -progress output: out_time_ms is microseconds too, and is ignored.
        feed(progress, """
                frame=0
                fps=0.00
                out_time_us=N/A
                out_time_ms=N/A
                out_time=N/A
                progress=continue
                frame=31
                out_time_us=1001000
                out_time_ms=1001000
                out_time=00:00:01.001000
                progress=continue
                frame=390
                out_time_us=12966667
                out_time_ms=12966667
                progress=end
                """);

        assertThat(progress.outTime()).contains(Duration.ofNanos(12_966_667_000L));
        assertThat(progress.ended()).isTrue();
    }

    @Test
    void notAvailableNegativeAndUnreadableValuesKeepTheLastValidOne() {
        FfmpegProgress progress = new FfmpegProgress();

        assertThat(progress.outTime()).isEmpty();
        progress.accept("out_time_us=N/A");
        progress.accept("out_time_us=-23220");
        assertThat(progress.outTime()).isEmpty();
        progress.accept("out_time_us=500000");
        for (String line : new String[] {"out_time_us=N/A", "out_time_us=-9223372036854775807", "out_time_us=", "out_time_us=1.5",
                "out_time_us=12abc", "out_time_us=99999999999999999999", "out_time_us=+7", "OUT_TIME_US=9", "out_time=00:00:09"}) {
            progress.accept(line);
        }
        assertThat(progress.outTime()).contains(Duration.ofMillis(500));
        assertThat(progress.ended()).isFalse();
        progress.accept("out_time_us=0");
        assertThat(progress.outTime()).contains(Duration.ZERO);
    }

    @Test
    void theLargestReadableValueDoesNotOverflow() {
        FfmpegProgress progress = new FfmpegProgress();

        progress.accept("out_time_us=999999999999999999");

        assertThat(progress.outTime()).contains(Duration.ofSeconds(999_999_999_999L, 999_999_000L));
    }

    @Test
    void crLineEndingsAndOverlongLinesStayBounded() {
        FfmpegProgress progress = new FfmpegProgress();
        LineOutput lines = new LineOutput(64, progress);

        byte[] text = ("out_time_us=2000000\rprogress=continue\r\n" + "x".repeat(10_000) + "\nout_time_us=3000000\n")
                .getBytes(StandardCharsets.UTF_8);
        lines.accept(text, text.length);
        lines.end();

        assertThat(progress.outTime()).contains(Duration.ofSeconds(3));
    }

    private static void feed(FfmpegProgress progress, String text) {
        LineOutput lines = new LineOutput(FfmpegHlsService.MAX_LINE_BYTES, progress);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        lines.accept(bytes, bytes.length);
        lines.end();
    }
}
