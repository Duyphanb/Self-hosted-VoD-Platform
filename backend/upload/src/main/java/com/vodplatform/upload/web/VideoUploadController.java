package com.vodplatform.upload.web;

import com.vodplatform.encoding.exception.UploadCommitUnknownException;
import com.vodplatform.encoding.exception.UploadDatabaseException;
import com.vodplatform.shared.error.ApiException;
import com.vodplatform.shared.web.RequestIds;
import com.vodplatform.upload.VideoAssetStatusResponse;
import com.vodplatform.upload.VideoUploadService;
import com.vodplatform.upload.exception.UploadInternalException;
import com.vodplatform.upload.exception.UploadStorageException;
import com.vodplatform.upload.exception.UploadValidationException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.util.UUID;

/**
 * Admin video upload ({@code POST /api/v1/admin/movies/{movieId}/video}): 202 once the raw object
 * is stored and the QUEUED asset/job pair is committed, even while broker publication is pending.
 *
 * <p>The URL rule for {@code /api/v1/admin/**} authorizes {@code ROLE_ADMIN} before any body is
 * parsed; {@code @PreAuthorize} is the second layer. The movie id binds first, so an invalid id is
 * rejected before the multipart body is resolved (lazily, see {@code resolve-lazily}). The part is
 * optional at binding so that a missing or non-multipart body reaches the service's 400 field
 * error instead of an undeclared status. There is no {@code consumes} condition (415 is not in
 * the contract) and no transaction: the service requires none.
 *
 * <p>Failures are mapped by {@link UploadExceptionHandler}. Any other failure is wrapped as
 * {@link UploadInternalException}, answered with the generic 500 {@code INTERNAL_ERROR}.
 */
@RestController
@RequestMapping("/api/v1/admin/movies")
@PreAuthorize("hasRole('ADMIN')")
public class VideoUploadController {

    private final VideoUploadService uploads;

    public VideoUploadController(VideoUploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping("/{movieId}/video")
    public ResponseEntity<VideoAssetStatusResponse> upload(
            @PathVariable UUID movieId,
            @RequestPart(name = "file", required = false) MultipartFile file,
            HttpServletRequest request) {
        try {
            requireOnlyFilePart(request, file);
            VideoAssetStatusResponse accepted =
                    uploads.upload(movieId, file, RequestIds.existing(request));
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
        } catch (ApiException
                | UploadStorageException
                | UploadDatabaseException
                | UploadCommitUnknownException mapped) {
            throw mapped;
        } catch (IOException | ServletException | RuntimeException unexpected) {
            throw new UploadInternalException(unexpected);
        }
    }

    /**
     * The contract declares exactly one {@code file} part. A missing file is the service's empty
     * file error; any additional part (another file, a text field or an unnamed part) is rejected
     * here, before anything is stored.
     */
    private static void requireOnlyFilePart(HttpServletRequest request, MultipartFile file)
            throws IOException, ServletException {
        if (file != null
                && request instanceof MultipartHttpServletRequest
                && request.getParts().size() != 1) {
            throw new UploadValidationException(UploadValidationException.EXACTLY_ONE_PART);
        }
    }
}
