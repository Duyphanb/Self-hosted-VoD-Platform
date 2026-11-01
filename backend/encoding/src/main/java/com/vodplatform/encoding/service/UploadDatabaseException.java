package com.vodplatform.encoding.service;
/** Service failure; HTTP mapping requires the 4.6 contract decision. */
public final class UploadDatabaseException extends RuntimeException {
    public UploadDatabaseException() { super("Upload database operation failed"); }
}
