package com.vodplatform.encoding.messaging;

/** Fixed safe reason: never retain or quote an untrusted body or parser exception. */
public final class InvalidEncodingJobMessageException extends IllegalArgumentException {
    public enum Reason { MALFORMED, UNSUPPORTED_VERSION, MESSAGE_ID_MISMATCH }
    private final Reason reason;

    public InvalidEncodingJobMessageException(Reason reason) {
        super("Invalid encoding job message: " + reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
