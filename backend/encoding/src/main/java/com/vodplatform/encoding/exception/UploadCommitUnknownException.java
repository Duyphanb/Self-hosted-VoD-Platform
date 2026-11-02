package com.vodplatform.encoding.exception;

/**
 * The commit could not be confirmed, so raw media is retained. The upload endpoint maps it to
 * 500 {@code UPLOAD_OUTCOME_UNKNOWN}.
 */
public final class UploadCommitUnknownException extends RuntimeException {
    public UploadCommitUnknownException() {
        super("Upload commit outcome is unknown");
    }
}
