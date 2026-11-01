package com.vodplatform.encoding.exception;

/**
 * The upload was not accepted: no asset/job pair was committed. The upload endpoint maps it to
 * 500 {@code UPLOAD_DATABASE_ERROR}.
 */
public final class UploadDatabaseException extends RuntimeException {
    public UploadDatabaseException() {
        super("Upload database operation failed");
    }
}
