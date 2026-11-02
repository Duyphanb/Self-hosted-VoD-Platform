package com.vodplatform.upload.web;

import com.vodplatform.encoding.exception.UploadCommitUnknownException;
import com.vodplatform.encoding.exception.UploadDatabaseException;
import com.vodplatform.shared.error.ApiError;
import com.vodplatform.shared.error.ApiErrorFactory;
import com.vodplatform.upload.exception.UploadInternalException;
import com.vodplatform.upload.exception.UploadStorageException;

import org.apache.catalina.connector.ClientAbortException;
import org.apache.coyote.BadRequestException;
import org.apache.tomcat.util.http.fileupload.FileUploadException;
import org.apache.tomcat.util.http.fileupload.InvalidFileNameException;
import org.apache.tomcat.util.http.fileupload.MultipartStream;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.io.EOFException;
import java.io.IOException;
import java.util.List;

/**
 * Upload-operation error mappings (owner decision on #174). Bodies use fixed messages and carry
 * the operation's {@code requestId}; they never contain keys, buckets, URLs or exception text.
 *
 * <ul>
 *   <li>413 {@code PAYLOAD_TOO_LARGE}: the multipart request or part-count limit.
 *   <li>400 {@code MALFORMED_REQUEST}: a malformed multipart body or a client abort.
 *   <li>503 {@code UPLOAD_STORAGE_UNAVAILABLE}: raw storage or spool I/O; not accepted.
 *   <li>500 {@code UPLOAD_DATABASE_ERROR}: the asset/job pair was not committed; not accepted.
 *   <li>500 {@code UPLOAD_OUTCOME_UNKNOWN}: the commit could not be confirmed; raw is retained.
 *   <li>500 {@code INTERNAL_ERROR}: any other failure; the result is unknown.
 * </ul>
 *
 * <p>Scoped to {@link VideoUploadController} with the highest precedence, so a later global
 * catch-all cannot take these types. It never handles {@code ApiException}, which stays with the
 * global handler. Multipart failures reach this advice only because parsing is lazy and so
 * happens after the handler is selected ({@code spring.servlet.multipart.resolve-lazily=true}).
 */
@RestControllerAdvice(assignableTypes = VideoUploadController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UploadExceptionHandler {

    private final ApiErrorFactory errors;

    public UploadExceptionHandler(ApiErrorFactory errors) {
        this.errors = errors;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge() {
        return errors.response(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "PAYLOAD_TOO_LARGE",
                "File exceeds upload size limit",
                List.of());
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ApiError> multipart(MultipartException exception) {
        return switch (classify(exception)) {
            case CLIENT ->
                    errors.response(
                            HttpStatus.BAD_REQUEST,
                            "MALFORMED_REQUEST",
                            "Malformed multipart request",
                            List.of());
            case SERVER_IO -> storageUnavailable(exception);
            case UNKNOWN -> internalError(exception);
        };
    }

    @ExceptionHandler(UploadStorageException.class)
    ResponseEntity<ApiError> storage() {
        return storageUnavailable(null);
    }

    @ExceptionHandler(UploadDatabaseException.class)
    ResponseEntity<ApiError> database() {
        return errors.response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "UPLOAD_DATABASE_ERROR",
                "Upload could not be recorded; it was not accepted",
                List.of());
    }

    @ExceptionHandler(UploadCommitUnknownException.class)
    ResponseEntity<ApiError> unknownOutcome() {
        return errors.response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "UPLOAD_OUTCOME_UNKNOWN",
                "Upload result is unknown; do not resubmit automatically",
                List.of());
    }

    @ExceptionHandler(UploadInternalException.class)
    ResponseEntity<ApiError> internal(UploadInternalException exception) {
        return internalError(exception.getCause());
    }

    /** {@code cause} is named (class only) in the single rejection log event when present. */
    private ResponseEntity<ApiError> storageUnavailable(Throwable cause) {
        return errors.response(
                HttpStatus.SERVICE_UNAVAILABLE,
                "UPLOAD_STORAGE_UNAVAILABLE",
                "Upload storage is temporarily unavailable; it was not accepted",
                List.of(),
                cause);
    }

    private ResponseEntity<ApiError> internalError(Throwable cause) {
        return errors.response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Unexpected server error; the upload result is unknown",
                List.of(),
                cause);
    }

    enum ParseFailure {
        CLIENT,
        SERVER_IO,
        UNKNOWN
    }

    /**
     * Classifies a multipart parse failure by the types in its cause chain, never by message. A
     * client abort, a malformed stream or chunk, an invalid part header or file name, or any other
     * file-upload protocol error is the client's fault; a plain I/O failure underneath (spool
     * directory, disk full, permissions) is the server's. This relies on Tomcat's multipart
     * implementation, the project's servlet container. A read-time protocol error is also answered
     * by the container ({@code sendError(400)}), so its body may be the container's, not an
     * {@code ApiError}.
     */
    static ParseFailure classify(MultipartException exception) {
        boolean protocolError = false;
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ClientAbortException
                    || cause instanceof EOFException
                    || cause instanceof BadRequestException
                    || cause instanceof MultipartStream.MalformedStreamException
                    || cause instanceof InvalidFileNameException
                    || cause instanceof IllegalArgumentException) {
                return ParseFailure.CLIENT;
            }
            if (cause instanceof FileUploadException) {
                protocolError = true;
            } else if (cause instanceof IOException) {
                return ParseFailure.SERVER_IO;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return protocolError ? ParseFailure.CLIENT : ParseFailure.UNKNOWN;
    }
}
