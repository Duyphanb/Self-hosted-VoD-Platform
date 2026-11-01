package com.vodplatform.upload.exception;

import com.vodplatform.shared.error.ApiException;

import org.springframework.http.HttpStatus;

/** Typed upload validation failure; contains no rejected input. */
public final class UnsupportedVideoTypeException extends ApiException {
    public UnsupportedVideoTypeException() {
        super(
                HttpStatus.BAD_REQUEST,
                "UNSUPPORTED_VIDEO_TYPE",
                "Unsupported video type or signature");
    }
}
