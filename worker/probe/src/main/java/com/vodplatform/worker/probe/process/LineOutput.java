package com.vodplatform.worker.probe.process;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Splits a stream into lines (LF or CR) and hands each non-empty line to a consumer, for FFprobe/FFmpeg log
 * lines and FFmpeg {@code -progress} output. Only {@code maxLineBytes} of a line are kept; the rest of a
 * longer line is dropped, so every line is seen but memory stays bounded.
 */
public final class LineOutput implements OutputSink {
    private final byte[] line;
    private final Consumer<String> lines;
    private int length;

    public LineOutput(int maxLineBytes, Consumer<String> lines) {
        if (maxLineBytes < 1) throw new IllegalArgumentException("Line cap must be positive");
        this.line = new byte[maxLineBytes];
        this.lines = Objects.requireNonNull(lines, "lines");
    }

    @Override
    public void accept(byte[] buffer, int length) {
        for (int i = 0; i < length; i++) {
            byte b = buffer[i];
            if (b == '\n' || b == '\r') {
                emit();
            } else if (this.length < line.length) {
                line[this.length++] = b;
            }
        }
    }

    @Override
    public void end() {
        emit();
    }

    private void emit() {
        if (length == 0) return;
        String text = new String(line, 0, length, StandardCharsets.UTF_8);
        length = 0;
        lines.accept(text);
    }
}
