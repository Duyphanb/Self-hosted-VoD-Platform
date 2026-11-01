package com.vodplatform.upload.exception;

/** Safe service failure; 4.6 owns its HTTP mapping. */
public final class UploadStorageException extends RuntimeException {
    public UploadStorageException() {
        super("Upload storage operation failed");
    }
}
