package com.vodplatform.upload.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vodplatform.encoding.exception.UploadCommitUnknownException;
import com.vodplatform.encoding.exception.UploadDatabaseException;
import com.vodplatform.shared.error.ApiErrorFactory;
import com.vodplatform.shared.error.ApiExceptionHandler;
import com.vodplatform.upload.VideoAssetStatusResponse;
import com.vodplatform.upload.VideoUploadService;
import com.vodplatform.upload.exception.UploadStorageException;
import com.vodplatform.upload.web.UploadExceptionHandler.ParseFailure;

import org.apache.catalina.connector.ClientAbortException;
import org.apache.tomcat.util.http.fileupload.FileUploadException;
import org.apache.tomcat.util.http.fileupload.MultipartStream;
import org.apache.tomcat.util.http.fileupload.impl.IOFileUploadException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartException;

import java.io.EOFException;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;

class UploadExceptionHandlerTest {

    private static final UUID MOVIE = UUID.fromString("6f1d2a4e-8c3b-4a5f-9e7d-1b2c3d4e5f60");

    private final VideoUploadService service = mock(VideoUploadService.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var errors = new ApiErrorFactory(Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC));
        mvc =
                MockMvcBuilders.standaloneSetup(new VideoUploadController(service))
                        .setControllerAdvice(
                                new UploadExceptionHandler(errors), new ApiExceptionHandler(errors))
                        .build();
    }

    private ResultActions upload(Supplier<RuntimeException> failure) throws Exception {
        when(service.upload(eq(MOVIE), any(), any())).thenThrow(failure.get());
        var file = new MockPart("file", "movie.mp4", new byte[] {0, 0, 0, 8, 'f', 't', 'y', 'p'});
        file.getHeaders().setContentType(MediaType.parseMediaType("video/mp4"));
        return mvc.perform(multipart("/api/v1/admin/movies/{movieId}/video", MOVIE).part(file));
    }

    @Test
    void storageFailureIsServiceUnavailable() throws Exception {
        upload(UploadStorageException::new)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("UPLOAD_STORAGE_UNAVAILABLE"))
                .andExpect(
                        jsonPath("$.message")
                                .value("Upload storage is temporarily unavailable; it was not accepted"));
    }

    @Test
    void databaseFailureIsNotAccepted() throws Exception {
        upload(UploadDatabaseException::new)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("UPLOAD_DATABASE_ERROR"));
    }

    @Test
    void unconfirmedCommitIsUnknown() throws Exception {
        upload(UploadCommitUnknownException::new)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("UPLOAD_OUTCOME_UNKNOWN"));
    }

    @Test
    void unexpectedFailureIsGenericInternalErrorWithoutDetails() throws Exception {
        var body =
                upload(() -> new IllegalStateException("secret key vod-raw/abc"))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).doesNotContain("secret", "vod-raw", "IllegalState");
    }

    @Test
    void acceptedUploadIsAcceptedStatus() throws Exception {
        when(service.upload(eq(MOVIE), any(), any())).thenReturn(VideoAssetStatusResponse.queued(UUID.randomUUID()));
        var file = new MockPart("file", "movie.mp4", new byte[] {0, 0, 0, 8, 'f', 't', 'y', 'p'});
        mvc.perform(multipart("/api/v1/admin/movies/{movieId}/video", MOVIE).part(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void parserFailuresAreClassifiedByType() {
        assertThat(UploadExceptionHandler.classify(wrap(new IOFileUploadException("m", new ClientAbortException()))))
                .isEqualTo(ParseFailure.CLIENT);
        assertThat(UploadExceptionHandler.classify(wrap(new IOFileUploadException("m", new EOFException()))))
                .isEqualTo(ParseFailure.CLIENT);
        assertThat(UploadExceptionHandler.classify(
                        wrap(new IOFileUploadException("m", new MultipartStream.MalformedStreamException("m")))))
                .isEqualTo(ParseFailure.CLIENT);
        assertThat(UploadExceptionHandler.classify(wrap(new FileUploadException("no boundary"))))
                .isEqualTo(ParseFailure.CLIENT);
        assertThat(UploadExceptionHandler.classify(wrap(new IOFileUploadException("m", new IOException("disk")))))
                .isEqualTo(ParseFailure.SERVER_IO);
        assertThat(UploadExceptionHandler.classify(wrap(new IOException("spool location"))))
                .isEqualTo(ParseFailure.SERVER_IO);
        assertThat(UploadExceptionHandler.classify(wrap(new IllegalStateException("other"))))
                .isEqualTo(ParseFailure.UNKNOWN);
        assertThat(UploadExceptionHandler.classify(new MultipartException("no cause")))
                .isEqualTo(ParseFailure.UNKNOWN);
    }

    private static MultipartException wrap(Throwable cause) {
        return new MultipartException("Failed to parse multipart servlet request", cause);
    }
}
