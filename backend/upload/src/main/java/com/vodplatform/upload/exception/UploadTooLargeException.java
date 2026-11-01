package com.vodplatform.upload.exception;

import com.vodplatform.shared.error.ApiException;

import org.springframework.http.HttpStatus;

/** Typed upload validation failure; contains no rejected input. */
public final class UploadTooLargeException extends ApiException {
    public UploadTooLargeException() {
        super(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "File exceeds upload size limit");
    }
}
