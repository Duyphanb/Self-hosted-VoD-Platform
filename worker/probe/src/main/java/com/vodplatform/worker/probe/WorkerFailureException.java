package com.vodplatform.worker.probe;

import java.util.Objects;

/**
 * A media step failed with an ADR-010 D28 code. The message is a fixed, safe summary: it never carries tool
 * diagnostics, local paths or media metadata (SECURITY.md Media Processing Safeguards).
 */
public final class WorkerFailureException extends RuntimeException {
    private final WorkerFailureCode code;

    public WorkerFailureException(WorkerFailureCode code, String message) {
        this(code, message, null);
    }

    public WorkerFailureException(WorkerFailureCode code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public WorkerFailureCode code() {
        return code;
    }
}
