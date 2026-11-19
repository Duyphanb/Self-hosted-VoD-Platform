package com.vodplatform.progress.exception;

import com.vodplatform.shared.error.ApiException;
import org.springframework.http.HttpStatus;

public class VideoAssetNotCurrentException extends ApiException {
    public VideoAssetNotCurrentException() {
        super(HttpStatus.CONFLICT, "VIDEO_ASSET_NOT_CURRENT", "Video asset is not current");
    }
}
