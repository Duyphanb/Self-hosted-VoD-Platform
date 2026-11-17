package com.vodplatform.encoding.exception;

import com.vodplatform.shared.error.ApiException;
import org.springframework.http.HttpStatus;

/** Uniform not-found response for HLS access and future admin asset operations. */
public class VideoAssetNotFoundException extends ApiException {
    public VideoAssetNotFoundException() {
        super(HttpStatus.NOT_FOUND, "VIDEO_ASSET_NOT_FOUND", "Video asset not found");
    }
}
