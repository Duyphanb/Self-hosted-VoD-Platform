package com.vodplatform.stream.web;

import com.vodplatform.encoding.exception.VideoAssetNotFoundException;
import com.vodplatform.playback.service.HlsAccess;
import com.vodplatform.playback.service.HlsAccessService;
import com.vodplatform.shared.storage.ObjectMissingException;
import com.vodplatform.shared.storage.ObjectStorage;
import com.vodplatform.shared.storage.StoredObject;
import com.vodplatform.shared.web.CanonicalUuid;
import com.vodplatform.shared.web.RequestIds;
import com.vodplatform.stream.exception.HlsStorageUnavailableException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** ADR-011 D4: synchronous bounded-memory copies, after the access transaction has ended. */
@RestController
public class HlsController {
    private static final Logger log = LoggerFactory.getLogger(HlsController.class);
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]+\\.ts");
    private final HlsAccessService access;
    private final ObjectStorage storage;

    public HlsController(HlsAccessService access, ObjectStorage storage) {
        this.access = access;
        this.storage = storage;
    }

    @GetMapping("/hls/video-assets/{videoAssetId}/master.m3u8")
    public void playlist(@PathVariable String videoAssetId, HttpServletResponse response) throws IOException {
        UUID id = parse(videoAssetId);
        HlsAccess allowed = access.requireAccess(id);
        stream(id, allowed.bucket(), allowed.masterKey(), "application/vnd.apple.mpegurl", response);
    }

    @GetMapping("/hls/video-assets/{videoAssetId}/segments/{segmentName}")
    public void segment(@PathVariable String videoAssetId, @PathVariable String segmentName,
                        HttpServletResponse response) throws IOException {
        UUID id = parse(videoAssetId);
        if (!SEGMENT.matcher(segmentName).matches()) throw new VideoAssetNotFoundException();
        HlsAccess allowed = access.requireAccess(id);
        stream(id, allowed.bucket(), allowed.attemptPrefix() + "segments/" + segmentName, "video/mp2t", response);
    }

    private static UUID parse(String text) {
        try {
            return CanonicalUuid.parse(text);
        } catch (IllegalArgumentException invalid) {
            throw new VideoAssetNotFoundException();
        }
    }

    private void stream(UUID id, String bucket, String key, String type, HttpServletResponse response)
            throws IOException {
        StoredObject object;
        try {
            object = storage.get(bucket, key);
        } catch (ObjectMissingException missing) {
            log.warn("HLS integrity failure assetId={} requestId={}", id, RequestIds.current().orElse(null));
            throw new VideoAssetNotFoundException();
        } catch (IOException unavailable) {
            throw new HlsStorageUnavailableException();
        }
        try {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(type);
            object.size().ifPresent(response::setContentLengthLong);
            byte[] buffer = new byte[16 * 1024];
            while (true) {
                int count;
                try {
                    count = object.read(buffer);
                } catch (IOException failure) {
                    if (response.isCommitted()) throw new IOException("HLS storage stream interrupted");
                    response.reset();
                    response.setHeader("Cache-Control", "no-store");
                    throw new HlsStorageUnavailableException();
                }
                if (count < 0) break;
                try {
                    response.getOutputStream().write(buffer, 0, count);
                } catch (IOException disconnected) {
                    // A client disconnect is expected; close storage without an error body or log.
                    return;
                }
            }
        } finally {
            try {
                object.close();
            } catch (IOException ignored) {
                // Closing an already consumed/interrupted storage response cannot repair the client response.
            }
        }
    }
}
