package com.vodplatform.encoding.exception;

import com.vodplatform.shared.error.ApiException;

import org.springframework.http.HttpStatus;

public final class VideoUploadInProgressException extends ApiException {
    public VideoUploadInProgressException() {
        super(
                HttpStatus.CONFLICT,
                "VIDEO_UPLOAD_IN_PROGRESS",
                "Another video upload is in progress");
    }
}
