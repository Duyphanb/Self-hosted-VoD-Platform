package com.vodplatform.upload.exception;

/**
 * An unexpected failure of the upload operation. The controller wraps any unmapped failure in this
 * type, so it is answered with the generic 500 {@code INTERNAL_ERROR} instead of the framework's
 * default error body. Only the cause's class name is ever logged.
 */
public final class UploadInternalException extends RuntimeException {
    public UploadInternalException(Exception cause) {
        super("Unexpected upload failure", cause);
    }
}
