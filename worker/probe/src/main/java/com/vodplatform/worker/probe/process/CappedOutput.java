package com.vodplatform.worker.probe.process;

import java.util.Arrays;

/** Keeps the first {@code maxBytes} of a stream and counts the rest, so a flood is drained without growing memory. */
public final class CappedOutput implements OutputSink {
    private final byte[] head;
    private int size;
    private long totalBytes;

    public CappedOutput(int maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("Output cap must be positive");
        this.head = new byte[maxBytes];
    }

    @Override
    public synchronized void accept(byte[] buffer, int length) {
        int kept = Math.min(length, head.length - size);
        System.arraycopy(buffer, 0, head, size, kept);
        size += kept;
        totalBytes += length;
    }

    /** The kept bytes; complete only when {@link #overflowed()} is false. */
    public synchronized byte[] bytes() {
        return Arrays.copyOf(head, size);
    }

    public synchronized boolean overflowed() {
        return totalBytes > head.length;
    }

    public synchronized long totalBytes() {
        return totalBytes;
    }
}
