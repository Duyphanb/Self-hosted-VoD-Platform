package com.vodplatform.upload;

import java.util.UUID;

public record VideoAssetStatusResponse(UUID videoAssetId, String status, String message) {
    public static VideoAssetStatusResponse queued(UUID id) {
        return new VideoAssetStatusResponse(id, "QUEUED", "Video queued for encoding");
    }
}
