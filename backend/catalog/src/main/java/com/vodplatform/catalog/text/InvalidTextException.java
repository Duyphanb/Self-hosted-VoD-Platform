package com.vodplatform.catalog.text;

/**
 * Thrown by {@link TextNormalizer} when a value must be rejected with 400
 * {@code VALIDATION_ERROR}. The message never contains the rejected value.
 */
public class InvalidTextException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /**
     * Why the value was rejected.
     */
    public enum Reason {
        /** Empty or whitespace-only after trimming. */
        BLANK,
        /** Contains a control character the field does not allow. */
        CONTROL_CHARACTER
    }

    private final Reason reason;

    InvalidTextException(Reason reason) {
        super(reason == Reason.BLANK ? "Text must not be blank" : "Text must not contain control characters");
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
