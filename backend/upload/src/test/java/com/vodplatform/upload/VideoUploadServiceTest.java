package com.vodplatform.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.vodplatform.catalog.exception.MovieNotFoundException;
import com.vodplatform.encoding.exception.UploadCommitUnknownException;
import com.vodplatform.encoding.exception.UploadDatabaseException;
import com.vodplatform.encoding.exception.VideoUploadInProgressException;
import com.vodplatform.encoding.messaging.EncodingJobMessage;
import com.vodplatform.encoding.service.EncodingJobService;
import com.vodplatform.shared.error.ApiException;
import com.vodplatform.shared.error.ApiFieldError;
import com.vodplatform.shared.storage.ObjectStorage;
import com.vodplatform.upload.exception.UploadStorageException;

import jakarta.servlet.MultipartConfigElement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

class VideoUploadServiceTest {

    private final ObjectStorage storage = mock(ObjectStorage.class);

    private final EncodingJobService jobs = mock(EncodingJobService.class);

    private final UUID movie = UUID.randomUUID();

    private final VideoUploadService service = service(UploadProperties.TYPES);

    private final byte[] iso = {0, 0, 0, 8, 'f', 't', 'y', 'p'};

    private final byte[] ebml = {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3};

    private VideoUploadService service(Set<String> types) {
        return new VideoUploadService(
                storage,
                jobs,
                new UploadProperties("vod-raw", types),
                new MultipartConfigElement("unused", 100, 100, 0));
    }

    private MockMultipartFile file(String type, byte[] body) {
        return new MockMultipartFile("file", "secret-do-not-use.txt", type, body) {
            @Override
            public byte[] getBytes() {
                throw new AssertionError("Whole file buffering forbidden");
            }
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"video/mp4", "video/quicktime", "video/x-matroska", "video/webm"})
    void streamsKnownLengthUsesTrustedExtensionAndCommitsBeforePublishing(String type)
            throws Exception {
        byte[] bytes = type.contains("mp4") || type.contains("quicktime") ? iso : ebml;
        var result = service.upload(movie, file(type, bytes), Optional.empty());
        assertThat(result.status()).isEqualTo("QUEUED");
        String ext =
                switch (type) {
                    case "video/mp4" -> "mp4";
                    case "video/quicktime" -> "mov";
                    case "video/webm" -> "webm";
                    default -> "mkv";
                };
        var order = inOrder(jobs, storage);
        order.verify(jobs).precheck(movie);
        order.verify(storage)
                .put(
                        eq("vod-raw"),
                        eq(movie + "/" + result.videoAssetId() + "." + ext),
                        any(InputStream.class),
                        eq((long) bytes.length),
                        eq(type));
        order.verify(jobs)
                .commitNewUpload(
                        eq(movie),
                        eq(result.videoAssetId()),
                        any(UUID.class),
                        eq("vod-raw"),
                        anyString());
        order.verify(jobs).publish(any(), eq(Optional.empty()));
        verify(storage, never()).delete(anyString(), anyString());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {"application/octet-stream", "text/plain", "video/unknown", "broken mime"})
    void rejectsDeclarationsBeforeDatabaseOrStorage(String mime) {
        assertInvalid(service, file(mime, iso), HttpStatus.BAD_REQUEST, "UNSUPPORTED_VIDEO_TYPE");
    }

    @Test
    void emptyFileMatchesRequestValidationContract() {
        ApiException failure =
                assertInvalid(
                        service,
                        file("video/mp4", new byte[0]),
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_ERROR");
        assertThat(failure).hasMessage("Request validation failed");
        assertThat(failure.getFieldErrors())
                .containsExactly(new ApiFieldError("file", "must not be empty"));
    }

    @Test
    void oversizeFileWithValidSignatureReturnsPayloadTooLarge() {
        byte[] body = Arrays.copyOf(iso, 101);
        assertInvalid(
                service,
                file("video/mp4", body),
                HttpStatus.PAYLOAD_TOO_LARGE,
                "PAYLOAD_TOO_LARGE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"video/mp4", "video/quicktime", "video/webm", "video/x-matroska"})
    void bothDirectionsOfFamilyMismatchAreRejected(String mime) {
        byte[] wrong = mime.contains("mp4") || mime.contains("quicktime") ? ebml : iso;
        assertInvalid(service, file(mime, wrong), HttpStatus.BAD_REQUEST, "UNSUPPORTED_VIDEO_TYPE");
    }

    @Test
    void narrowedAllowlistRejectsValidWebm() {
        assertInvalid(
                service(Set.of("video/mp4")),
                file("video/webm", ebml),
                HttpStatus.BAD_REQUEST,
                "UNSUPPORTED_VIDEO_TYPE");
    }

    @Test
    void legacyQuickTimeAtomWithoutFtypIsRejected() {
        byte[] atom = {0, 0, 0, 8, 'w', 'i', 'd', 'e'};
        assertInvalid(
                service,
                file("video/quicktime", atom),
                HttpStatus.BAD_REQUEST,
                "UNSUPPORTED_VIDEO_TYPE");
    }

    @Test
    void truncatedEbmlPrefixIsRejected() {
        assertInvalid(
                service,
                file("video/webm", Arrays.copyOf(ebml, 3)),
                HttpStatus.BAD_REQUEST,
                "UNSUPPORTED_VIDEO_TYPE");
    }

    private ApiException assertInvalid(
            VideoUploadService target, MockMultipartFile file, HttpStatus status, String code) {
        ApiException failure =
                catchThrowableOfType(
                        ApiException.class, () -> target.upload(movie, file, Optional.empty()));
        assertThat(failure).isNotNull();
        assertThat(failure.getStatus()).isEqualTo(status);
        assertThat(failure.getCode()).isEqualTo(code);
        verifyNoInteractions(jobs, storage);
        return failure;
    }

    @Test
    void putFailureCompensatesAndNeverCommits() throws Exception {
        doThrow(new IOException("untrusted-secret"))
                .when(storage)
                .put(anyString(), anyString(), any(), anyLong(), anyString());
        assertThatThrownBy(() -> service.upload(movie, file("video/mp4", iso), Optional.empty()))
                .isInstanceOf(UploadStorageException.class)
                .hasMessage("Upload storage operation failed")
                .hasNoCause();
        verify(storage).delete(eq("vod-raw"), matches(movie + "/[0-9a-f-]+\\.mp4"));
        verify(jobs, never()).commitNewUpload(any(), any(), any(), anyString(), anyString());
    }

    @Test
    void rollbackCompensatesButUnknownCommitRetainsRaw() throws Exception {
        doThrow(new UploadDatabaseException())
                .when(jobs)
                .commitNewUpload(any(), any(), any(), anyString(), anyString());
        assertThatThrownBy(() -> service.upload(movie, file("video/mp4", iso), Optional.empty()))
                .isInstanceOf(UploadDatabaseException.class);
        verify(storage).delete(anyString(), anyString());
        reset(storage);
        doThrow(new UploadCommitUnknownException())
                .when(jobs)
                .commitNewUpload(any(), any(), any(), anyString(), anyString());
        assertThatThrownBy(() -> service.upload(movie, file("video/mp4", iso), Optional.empty()))
                .isInstanceOf(UploadCommitUnknownException.class);
        verify(storage, never()).delete(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void compensationWarnContainsOnlyIdsAndSafeRequestIdAndNeverMasksConflict(boolean deleteFails)
            throws Exception {
        doThrow(new VideoUploadInProgressException())
                .when(jobs)
                .commitNewUpload(any(), any(), any(), anyString(), anyString());
        if (deleteFails) {
            doThrow(new IOException("secret-object-key\nforged-log"))
                    .when(storage)
                    .delete(anyString(), anyString());
        }
        Logger logger = (Logger) LoggerFactory.getLogger(VideoUploadService.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        logger.addAppender(capture);
        try {
            assertThatThrownBy(
                            () ->
                                    service.upload(
                                            movie,
                                            file("video/mp4", iso),
                                            Optional.of("safe-id_123")))
                    .isInstanceOf(VideoUploadInProgressException.class);
            org.mockito.ArgumentCaptor<UUID> asset =
                    org.mockito.ArgumentCaptor.forClass(UUID.class);
            org.mockito.ArgumentCaptor<UUID> job = org.mockito.ArgumentCaptor.forClass(UUID.class);
            verify(jobs)
                    .commitNewUpload(
                            eq(movie), asset.capture(), job.capture(), eq("vod-raw"), anyString());
            List<ILoggingEvent> warnings =
                    capture.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
            assertThat(warnings).hasSize(1);
            ILoggingEvent event = warnings.getFirst();
            assertThat(event.getFormattedMessage())
                    .isEqualTo(
                            (deleteFails ? "upload compensation failed" : "upload compensated")
                                    + " movieId="
                                    + movie
                                    + " videoAssetId="
                                    + asset.getValue()
                                    + " encodingJobId="
                                    + job.getValue()
                                    + " requestId=safe-id_123");
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(capture.list)
                    .allSatisfy(
                            log -> {
                                assertThat(log.getFormattedMessage())
                                        .doesNotContain("vod-raw", "secret", ".mp4", "forged-log");
                                assertThat(log.getThrowableProxy()).isNull();
                            });
        } finally {
            logger.detachAppender(capture);
            capture.stop();
        }
    }

    static Stream<Arguments> requestIds() {
        return Stream.of(
                Arguments.of("request-id_123", true),
                Arguments.of("a".repeat(63) + ".", true),
                Arguments.of("a".repeat(64), true),
                Arguments.of("a".repeat(65), false),
                Arguments.of("bad\ninjected", false),
                Arguments.of("bad space", false),
                Arguments.of("bad/slash", false),
                Arguments.of("", false));
    }

    @ParameterizedTest
    @MethodSource("requestIds")
    void rawStoredAndAcceptedLogsContainOnlyIdsAndSanitizedRequestId(
            String requestId, boolean accepted) {
        Logger logger = (Logger) LoggerFactory.getLogger(VideoUploadService.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        logger.addAppender(capture);
        try {
            var result = service.upload(movie, file("video/mp4", iso), Optional.of(requestId));
            var message = ArgumentCaptor.forClass(EncodingJobMessage.class);
            verify(jobs)
                    .publish(
                            message.capture(),
                            eq(accepted ? Optional.of(requestId) : Optional.empty()));
            assertThat(message.getValue().videoAssetId()).isEqualTo(result.videoAssetId());
            String ids =
                    " movieId="
                            + movie
                            + " videoAssetId="
                            + result.videoAssetId()
                            + " encodingJobId="
                            + message.getValue().encodingJobId()
                            + " requestId="
                            + (accepted ? requestId : "null");
            assertThat(capture.list).hasSize(2);
            assertThat(capture.list.getFirst().getFormattedMessage())
                    .isEqualTo("raw object stored" + ids);
            assertThat(capture.list.getLast().getFormattedMessage())
                    .isEqualTo("upload accepted" + ids);
            capture.list.forEach(
                    event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.INFO);
                        assertThat(event.getFormattedMessage())
                                .doesNotContain(
                                        "vod-raw",
                                        "secret-do-not-use.txt",
                                        movie + "/" + result.videoAssetId() + ".mp4");
                        assertThat(event.getThrowableProxy()).isNull();
                    });
        } finally {
            logger.detachAppender(capture);
            capture.stop();
        }
    }

    @Test
    void precheckConflictAndMissingMovieNeverPut() {
        for (RuntimeException failure :
                List.of(new VideoUploadInProgressException(), new MovieNotFoundException())) {
            doThrow(failure).when(jobs).precheck(movie);
            assertThatThrownBy(
                            () -> service.upload(movie, file("video/mp4", iso), Optional.empty()))
                    .isSameAs(failure);
        }
        verifyNoInteractions(storage);
        verify(jobs, never()).commitNewUpload(any(), any(), any(), anyString(), anyString());
        verify(jobs, never()).publish(any(), any());
    }

    @Test
    void configuredAllowlistCannotExpandFrozenFamilies() {
        assertThatThrownBy(() -> new UploadProperties("vod-raw", Set.of("text/plain")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UploadProperties("vod-raw", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"#EXTM3U", "ffconcat version 1.0", "ordinary text", "legacy QuickTime"})
    void renamedTextAndPlaylistsAreRejectedEvenWithAllowedMime(String body) {
        assertInvalid(
                service,
                file("video/mp4", body.getBytes(StandardCharsets.US_ASCII)),
                HttpStatus.BAD_REQUEST,
                "UNSUPPORTED_VIDEO_TYPE");
    }

    @Test
    void bothStreamsCloseAndDeclaredMimeParametersAreCanonicalized() throws Exception {
        var closed = new AtomicInteger();
        var file =
                new MockMultipartFile("file", "ignored.webm", "VIDEO/MP4; charset=UTF-8", iso) {
                    @Override
                    public InputStream getInputStream() {
                        return new FilterInputStream(new ByteArrayInputStream(iso)) {
                            @Override
                            public void close() throws IOException {
                                closed.incrementAndGet();
                                super.close();
                            }
                        };
                    }
                };
        service.upload(movie, file, Optional.empty());
        assertThat(closed.get()).isEqualTo(2);
        verify(storage).put(eq("vod-raw"), endsWith(".mp4"), any(), eq(8L), eq("video/mp4"));
    }
}
