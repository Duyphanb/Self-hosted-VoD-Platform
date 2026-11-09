package com.vodplatform.worker.probe.process;

/**
 * Receives one output stream of a media process. {@link MediaProcessRunner} drains stdout and stderr on two
 * threads and calls each sink from its own drain thread only, so an implementation must stay bounded but
 * need not be thread safe against itself.
 */
public interface OutputSink {

    /** Accepts the next {@code length} bytes of {@code buffer}; the buffer is reused after the call. */
    void accept(byte[] buffer, int length);

    /** Called once when the stream ends or the process was killed. */
    default void end() {
    }
}
