package com.vodplatform.upload.exception;

/**
 * Raw storage or spool I/O failed before commit, so the upload was not accepted. The upload
 * endpoint maps it to 503 {@code UPLOAD_STORAGE_UNAVAILABLE}.
 */
public final class UploadStorageException extends RuntimeException {
    public UploadStorageException() {
        super("Upload storage operation failed");
    }
}
