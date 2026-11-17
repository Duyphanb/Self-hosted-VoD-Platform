package com.vodplatform.encoding.exception;

import com.vodplatform.shared.error.ApiException;
import org.springframework.http.HttpStatus;

/** Shared by playback and progress when a visible movie has no current READY asset. */
public class VideoNotReadyException extends ApiException {
    public VideoNotReadyException() {
        super(HttpStatus.CONFLICT, "VIDEO_NOT_READY", "Video is not ready");
    }
}
