package com.vodplatform.upload;

import com.vodplatform.encoding.exception.UploadCommitUnknownException;
import com.vodplatform.encoding.messaging.EncodingJobMessage;
import com.vodplatform.encoding.service.EncodingJobService;
import com.vodplatform.shared.storage.ObjectStorage;
import com.vodplatform.upload.exception.UnsupportedVideoTypeException;
import com.vodplatform.upload.exception.UploadStorageException;
import com.vodplatform.upload.exception.UploadTooLargeException;
import com.vodplatform.upload.exception.UploadValidationException;

import jakarta.servlet.MultipartConfigElement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stores raw media before committing the asset/job pair. Callers must have no ambient transaction.
 * Validation and movie conflicts use typed ApiExceptions. Storage, database and unresolved commit
 * failures remain unmapped service exceptions until the endpoint contract is implemented in #174.
 * An unresolved commit retains raw media; a known failed write is compensated best effort.
 */
@Service
public class VideoUploadService {
    private static final Logger log = LoggerFactory.getLogger(VideoUploadService.class);

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final ObjectStorage storage;

    private final EncodingJobService jobs;

    private final UploadProperties properties;

    private final MultipartConfigElement multipart;

    public VideoUploadService(
            ObjectStorage storage,
            EncodingJobService jobs,
            UploadProperties properties,
            MultipartConfigElement multipart) {
        this.storage = storage;
        this.jobs = jobs;
        this.properties = properties;
        this.multipart = multipart;
    }

    public VideoAssetStatusResponse upload(
            UUID movieId, MultipartFile file, Optional<String> requestId) {
        EncodingJobService.requireNoTransaction();
        Objects.requireNonNull(movieId, "movieId");
        Optional<String> safeRequestId =
                Objects.requireNonNull(requestId, "requestId")
                        .filter(value -> REQUEST_ID.matcher(value).matches());
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw new UploadValidationException();
        }
        if (file.getSize() > multipart.getMaxFileSize()) {
            throw new UploadTooLargeException();
        }
        String mime;
        try {
            var declared = MimeType.valueOf(Objects.requireNonNullElse(file.getContentType(), ""));
            mime = (declared.getType() + "/" + declared.getSubtype()).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException invalid) {
            throw new UnsupportedVideoTypeException();
        }
        if (!properties.allowedMimeTypes().contains(mime)) {
            throw new UnsupportedVideoTypeException();
        }
        boolean iso = mime.equals("video/mp4") || mime.equals("video/quicktime");
        try (var input = file.getInputStream()) {
            byte[] prefix = input.readNBytes(8);
            boolean matches =
                    iso
                            ? prefix.length >= 8
                                    && prefix[4] == 'f'
                                    && prefix[5] == 't'
                                    && prefix[6] == 'y'
                                    && prefix[7] == 'p'
                            : prefix.length >= 4
                                    && prefix[0] == 0x1a
                                    && prefix[1] == 0x45
                                    && prefix[2] == (byte) 0xdf
                                    && prefix[3] == (byte) 0xa3;
            if (!matches) {
                throw new UnsupportedVideoTypeException();
            }
        } catch (IOException failure) {
            throw new UploadStorageException();
        }
        jobs.precheck(movieId);
        UUID assetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String extension =
                switch (mime) {
                    case "video/mp4" -> "mp4";
                    case "video/quicktime" -> "mov";
                    case "video/webm" -> "webm";
                    default -> "mkv";
                };
        String key = movieId + "/" + assetId + "." + extension;
        try (var input = file.getInputStream()) {
            storage.put(properties.rawBucket(), key, input, file.getSize(), mime);
        } catch (IOException | RuntimeException failure) {
            compensate(movieId, assetId, jobId, key, safeRequestId);
            throw new UploadStorageException();
        }
        log.info(
                "raw object stored movieId={} videoAssetId={} encodingJobId={} requestId={}",
                movieId,
                assetId,
                jobId,
                safeRequestId.orElse(null));
        try {
            jobs.commitNewUpload(movieId, assetId, jobId, properties.rawBucket(), key);
        } catch (UploadCommitUnknownException unknown) {
            throw unknown;
        } catch (RuntimeException failure) {
            compensate(movieId, assetId, jobId, key, safeRequestId);
            throw failure;
        }
        jobs.publish(new EncodingJobMessage(jobId, assetId, 1), safeRequestId);
        log.info(
                "upload accepted movieId={} videoAssetId={} encodingJobId={} requestId={}",
                movieId,
                assetId,
                jobId,
                safeRequestId.orElse(null));
        return VideoAssetStatusResponse.queued(assetId);
    }

    private void compensate(
            UUID movieId, UUID assetId, UUID jobId, String key, Optional<String> requestId) {
        try {
            storage.delete(properties.rawBucket(), key);
            log.warn(
                    "upload compensated movieId={} videoAssetId={} encodingJobId={} requestId={}",
                    movieId,
                    assetId,
                    jobId,
                    requestId.orElse(null));
        } catch (IOException | RuntimeException failure) {
            log.warn(
                    "upload compensation failed movieId={} videoAssetId={} encodingJobId={}"
                            + " requestId={}",
                    movieId,
                    assetId,
                    jobId,
                    requestId.orElse(null));
        }
    }
}
