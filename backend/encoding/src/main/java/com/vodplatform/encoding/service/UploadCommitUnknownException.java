package com.vodplatform.encoding.service;
/** Service failure; HTTP mapping requires the 4.6 contract decision. */
public final class UploadCommitUnknownException extends RuntimeException {
    public UploadCommitUnknownException() { super("Upload commit outcome is unknown"); }
}
