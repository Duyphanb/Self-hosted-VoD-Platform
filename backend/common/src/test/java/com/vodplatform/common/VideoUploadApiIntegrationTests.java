package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vodplatform.encoding.exception.UploadCommitUnknownException;
import com.vodplatform.encoding.exception.UploadDatabaseException;
import com.vodplatform.encoding.messaging.EncodingJobPublisher;
import com.vodplatform.encoding.messaging.PublishOutcome;
import com.vodplatform.encoding.service.EncodingJobService;
import com.vodplatform.shared.storage.ObjectStorage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockPart;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The upload operation through the assembled application (bearer chain, route gate, controller,
 * service and H2), with storage and the broker mocked. Every response is checked against the API
 * contract by the auto-configured MockMvc.
 */
@SpringBootTest(properties = {"auth.tokens.secret=test-only-secret-with-at-least-32-bytes"})
@AutoConfigureMockMvc
class VideoUploadApiIntegrationTests {

    private static final byte[] MP4 = {0, 0, 0, 8, 'f', 't', 'y', 'p'};

    @Autowired MockMvc mvc;

    @Autowired JdbcTemplate jdbc;

    @Autowired JwtEncoder jwtEncoder;

    @MockitoBean ObjectStorage storage;

    @MockitoBean EncodingJobPublisher publisher;

    @MockitoSpyBean EncodingJobService jobs;

    private final List<UUID> movies = new ArrayList<>();

    @BeforeEach
    void configure() throws Exception {
        when(publisher.confirmTimeout()).thenReturn(Duration.ofSeconds(5));
        when(publisher.publish(any(), any())).thenReturn(new PublishOutcome.Confirmed());
        doAnswer(call -> ((InputStream) call.getArgument(2)).readAllBytes())
                .when(storage)
                .put(anyString(), anyString(), any(), anyLong(), anyString());
    }

    @AfterEach
    void clean() {
        for (UUID id : movies) {
            jdbc.update(
                    "delete from encoding_jobs where video_asset_id in "
                            + "(select id from video_assets where movie_id=?)",
                    id);
            jdbc.update("delete from video_assets where movie_id=?", id);
            jdbc.update("delete from movies where id=?", id);
        }
    }

    private UUID movie(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into movies (id,title,slug,description,status,created_at,updated_at)"
                        + " values (?,?,?,?,?,?,?)",
                id,
                "Upload API fixture",
                "upload-api-" + id,
                "fixture",
                status,
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
        movies.add(id);
        return id;
    }

    private String admin() {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        var claims =
                JwtClaimsSet.builder()
                        .subject(id)
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .claim("userId", id)
                        .claim("email", "upload-admin@example.com")
                        .claim("roles", List.of("ROLE_ADMIN"))
                        .build();
        return "Bearer "
                + jwtEncoder
                        .encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                                        claims))
                        .getTokenValue();
    }

    private ResultActions upload(UUID movieId, String declaredType, String requestId) throws Exception {
        var file = new MockPart("file", "untrusted-name.exe", MP4);
        file.getHeaders().setContentType(MediaType.parseMediaType(declaredType));
        var request =
                multipart("/api/v1/admin/movies/{movieId}/video", movieId)
                        .part(file)
                        .header("Authorization", admin());
        if (requestId != null) {
            request.header("X-Request-ID", requestId);
        }
        return mvc.perform(request);
    }

    private long pendingJobs(UUID movieId) {
        Long count =
                jdbc.queryForObject(
                        "select count(*) from encoding_jobs j join video_assets a on a.id=j.video_asset_id"
                                + " where a.movie_id=? and j.status='QUEUED' and j.published_at is null",
                        Long.class,
                        movieId);
        return count == null ? 0 : count;
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PUBLISHED", "ARCHIVED"})
    void acceptedForEveryMovieStatusEvenWhilePublicationIsPending(String movieStatus) throws Exception {
        when(publisher.publish(any(), any())).thenReturn(new PublishOutcome.TimedOut());
        UUID movieId = movie(movieStatus);

        upload(movieId, "video/mp4", "upload.pending_1")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.videoAssetId").isString())
                .andExpect(jsonPath("$.requestId").doesNotExist());

        assertThat(pendingJobs(movieId)).isEqualTo(1);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).put(eq("vod-raw"), key.capture(), any(), eq(8L), eq("video/mp4"));
        assertThat(key.getValue()).startsWith(movieId + "/").endsWith(".mp4");
    }

    @Test
    void inFlightUploadIsConflictWithRequestIdAndNoSecondPut() throws Exception {
        UUID movieId = movie("DRAFT");
        upload(movieId, "video/mp4", null).andExpect(status().isAccepted());

        upload(movieId, "video/mp4", "upload.conflict_1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VIDEO_UPLOAD_IN_PROGRESS"))
                .andExpect(jsonPath("$.requestId").value("upload.conflict_1"));

        verify(storage, times(1)).put(anyString(), anyString(), any(), anyLong(), anyString());
    }

    @Test
    void unsupportedDeclaredTypeIsRejectedBeforeStorage() throws Exception {
        upload(movie("DRAFT"), "text/plain", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_VIDEO_TYPE"))
                .andExpect(jsonPath("$.requestId").isString());

        verify(storage, never()).put(anyString(), anyString(), any(), anyLong(), anyString());
    }

    @Test
    void storageFailureIsUnavailableAndCompensatesThisKeyOnly() throws Exception {
        doThrow(new IOException("storage down"))
                .when(storage)
                .put(anyString(), anyString(), any(), anyLong(), anyString());
        UUID movieId = movie("DRAFT");

        String body =
                upload(movieId, "video/mp4", "upload.storage_1")
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("UPLOAD_STORAGE_UNAVAILABLE"))
                        .andExpect(jsonPath("$.requestId").value("upload.storage_1"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).put(eq("vod-raw"), key.capture(), any(), anyLong(), anyString());
        verify(storage).delete("vod-raw", key.getValue());
        assertThat(pendingJobs(movieId)).isZero();
        assertThat(body).doesNotContain("vod-raw", key.getValue(), "storage down");
    }

    @Test
    void databaseFailureIsNotAcceptedAndCompensates() throws Exception {
        doThrow(new UploadDatabaseException()).when(jobs).commitNewUpload(any(), any(), any(), any(), any());
        UUID movieId = movie("DRAFT");

        upload(movieId, "video/mp4", null)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("UPLOAD_DATABASE_ERROR"))
                .andExpect(jsonPath("$.requestId").isString());

        verify(storage).delete(eq("vod-raw"), anyString());
    }

    @Test
    void unconfirmedCommitIsUnknownAndRetainsRaw() throws Exception {
        doThrow(new UploadCommitUnknownException()).when(jobs).commitNewUpload(any(), any(), any(), any(), any());

        upload(movie("DRAFT"), "video/mp4", "upload.unknown_1")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("UPLOAD_OUTCOME_UNKNOWN"))
                .andExpect(jsonPath("$.requestId").value("upload.unknown_1"));

        verify(storage, never()).delete(anyString(), anyString());
    }

    @Test
    void unexpectedFailureIsGenericInternalError() throws Exception {
        doThrow(new IllegalStateException("bug with vod-raw/key"))
                .when(jobs)
                .commitNewUpload(any(), any(), any(), any(), any());

        String body =
                upload(movie("DRAFT"), "video/mp4", null)
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                        .andExpect(jsonPath("$.requestId").isString())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContain("bug", "vod-raw", "IllegalState");
    }

    @Test
    void unsafeRequestIdIsReplacedNotEchoed() throws Exception {
        String body =
                upload(UUID.randomUUID(), "video/mp4", "bad id/with space")
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("MOVIE_NOT_FOUND"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContain("bad id");
        assertThat(body).containsPattern("\"requestId\":\"[0-9a-f-]{36}\"");
    }
}
