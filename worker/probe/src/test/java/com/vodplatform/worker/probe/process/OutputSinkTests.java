package com.vodplatform.worker.probe.process;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutputSinkTests {

    @Test
    void cappedOutputKeepsTheHeadAndCountsEverything() {
        CappedOutput output = new CappedOutput(5);

        feed(output, "abc");
        assertThat(output.overflowed()).isFalse();
        feed(output, "defgh");

        assertThat(new String(output.bytes(), UTF_8)).isEqualTo("abcde");
        assertThat(output.totalBytes()).isEqualTo(8);
        assertThat(output.overflowed()).isTrue();
    }

    @Test
    void cappedOutputAtExactlyItsCapIsNotOverflowed() {
        CappedOutput output = new CappedOutput(3);

        feed(output, "abc");

        assertThat(output.overflowed()).isFalse();
        assertThat(new String(output.bytes(), UTF_8)).isEqualTo("abc");
    }

    @Test
    void lineOutputSplitsOnLfAndCrAcrossChunksAndFlushesTheLastLine() {
        List<String> lines = new ArrayList<>();
        LineOutput output = new LineOutput(64, lines::add);

        feed(output, "[mov @ 0x1] first\n[matroska @ 0x2] sec");
        feed(output, "ond\r\n\nprogress=continue\rlast");
        output.end();

        assertThat(lines).containsExactly("[mov @ 0x1] first", "[matroska @ 0x2] second", "progress=continue", "last");
    }

    @Test
    void lineOutputTruncatesLongLinesButStillSeesTheNextLine() {
        List<String> lines = new ArrayList<>();
        LineOutput output = new LineOutput(4, lines::add);

        feed(output, "x".repeat(10_000) + "\nnext\n");

        assertThat(lines).containsExactly("xxxx", "next");
    }

    @Test
    void capsMustBePositive() {
        assertThatThrownBy(() -> new CappedOutput(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LineOutput(0, line -> { })).isInstanceOf(IllegalArgumentException.class);
    }

    private static void feed(OutputSink sink, String text) {
        byte[] bytes = text.getBytes(UTF_8);
        byte[] buffer = new byte[bytes.length + 7];
        System.arraycopy(bytes, 0, buffer, 0, bytes.length);
        sink.accept(buffer, bytes.length);
    }
}
