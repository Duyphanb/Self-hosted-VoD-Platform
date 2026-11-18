package com.vodplatform.stream.exception;

import com.vodplatform.shared.error.ApiException;
import org.springframework.http.HttpStatus;

/** A storage failure before the media response commits; carries no transport diagnostics. */
public class HlsStorageUnavailableException extends ApiException {
    public HlsStorageUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "HLS_STORAGE_UNAVAILABLE", "HLS storage is unavailable");
    }
}
