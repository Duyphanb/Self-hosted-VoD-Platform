package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vodplatform.common.support.BearerSessionFixture;
import com.vodplatform.common.support.PostgresTestDatabase;
import com.vodplatform.shared.storage.ObjectMissingException;
import com.vodplatform.shared.storage.ObjectStorage;
import com.vodplatform.shared.storage.StoredObject;
import jakarta.persistence.EntityManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Committed isolated fixtures: no test transaction can hide a connection held during streaming. */
@SpringBootTest(properties = {
        "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
        "auth.tokens.access-token-ttl=15m", "auth.tokens.refresh-token-ttl=7d"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class HlsApiIntegrationTests implements PostgresTestDatabase {
    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
    private static final byte[] PLAYLIST = "#EXTM3U\n#EXTINF:6.0,\nsegments/seg_00001.ts\n#EXT-X-ENDLIST\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] SEGMENT = new byte[] {0x47, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    @Autowired private MockMvc mvc;
    @RegisterExtension
    final BearerSessionFixture bearerSessions = new BearerSessionFixture();
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private EntityManagerFactory emf;
    @MockitoBean private ObjectStorage storage;
    private final List<UUID> movies = new ArrayList<>();
    private final AtomicInteger closed = new AtomicInteger();
    private ObjectNode expected404;
    private List<String> expected404Headers;

    @BeforeEach
    void readableStorage() throws Exception {
        when(storage.get(anyString(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            byte[] body = call.<String>getArgument(1).endsWith("master.m3u8") ? PLAYLIST : SEGMENT;
            return object(body, body.length);
        });
    }

    @AfterEach
    void cleanup() {
        for (UUID movie : movies) jdbc.update("delete from movies where id = ?", movie);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_ADMIN"})
    void servesExactObjectsWithTwoMediaReadsAndOneColdSessionRead(String role) throws Exception {
        UUID id = ready(movie("PUBLISHED"));
        String token = bearer(role);
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        boolean enabled = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        try {
            for (boolean segment : List.of(false, true)) {
                for (boolean range : List.of(false, true)) {
                    stats.clear();
                    var request = request(path(id, segment), token);
                    if (range) {
                        request.header("Range", "bytes=0-9");
                    }
                    MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
                    assertMedia(result, segment, true);
                    int expectedStatements = !segment && !range ? 3 : 2;
                    assertThat(stats.getPrepareStatementCount()).isEqualTo(expectedStatements);
                }
            }
        } finally {
            stats.setStatisticsEnabled(enabled);
        }
        verify(storage, org.mockito.Mockito.times(2)).get("vod-hls", key(id));
        verify(storage, org.mockito.Mockito.times(2)).get("vod-hls", prefix(id) + "segments/seg_00001.ts");
        assertThat(closed).hasValue(4);
    }

    @Test
    void unknownLengthStillStreamsWholeObject() throws Exception {
        UUID id = ready(movie("PUBLISHED"));
        when(storage.get(anyString(), anyString())).thenAnswer(call -> object(SEGMENT, -1));
        assertMedia(mvc.perform(request(path(id, true), bearer("ROLE_USER")))
                .andExpect(status().isOk()).andReturn(), true, false);
        assertThat(closed).hasValue(1);
    }

    @Test
    void onlyHeaderAuthenticatesHlsAndPlayback(CapturedOutput output) throws Exception {
        UUID id = ready(movie("PUBLISHED"));
        String token = bearer("ROLE_USER");
        for (String path : List.of(path(id, false), path(id, true),
                "/api/v1/movies/" + movies.getFirst() + "/playback")) {
            mvc.perform(request(path, token)).andExpect(status().isOk());
            for (var request : List.of(request(path, null), request(path, "invalid-token"),
                    request(path, null).queryParam("access_token", token))) {
                MvcResult result = mvc.perform(request).andExpect(status().isUnauthorized()).andReturn();
                assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("code").asString())
                        .isEqualTo("UNAUTHORIZED");
                if (path.startsWith("/hls/")) assertNoStore(result);
            }
        }
        assertThat(output.getAll()).contains("HLS access denied status=401 requestId=hls-test220 assetId=" + id)
                .doesNotContain(token, "access_token=", "Authorization:");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_ADMIN"})
    void allAccessFailuresAreIdenticalAndDoNotReadStorage(String role) throws Exception {
        String token = bearer(role);
        UUID movie = movie("PUBLISHED");
        for (boolean segment : List.of(false, true)) {
            notFound(path(UUID.randomUUID(), segment), token);
            for (String state : List.of("UPLOADED", "QUEUED", "PROCESSING", "FAILED")) {
                UUID id = asset(UUID.randomUUID(), movie, state, T0);
                notFound(path(id, segment), token);
                jdbc.update("delete from video_assets where id = ?", id);
            }
            for (String state : List.of("DRAFT", "ARCHIVED")) {
                notFound(path(ready(movie(state)), segment), token);
            }
        }
        verifyNoInteractions(storage);
    }

    @Test
    void nextSegmentRechecksReplacementAndMovieVisibility(CapturedOutput output) throws Exception {
        UUID movie = movie("PUBLISHED");
        UUID old = ready(movie);
        String token = bearer("ROLE_ADMIN");
        mvc.perform(request(path(old, false), token)).andExpect(status().isOk());
        mvc.perform(request(path(old, true), token)).andExpect(status().isOk());
        UUID next = asset(UUID.randomUUID(), movie, "READY", T0.plusSeconds(1));
        clearInvocations(storage);
        notFound(path(old, false), token);
        notFound(path(old, true), token);
        verifyNoInteractions(storage);
        mvc.perform(request(path(next, true), token)).andExpect(status().isOk());
        jdbc.update("update movies set status='ARCHIVED' where id=?", movie);
        clearInvocations(storage);
        notFound(path(next, true), token);
        verifyNoInteractions(storage);
        assertThat(output.getAll()).contains("HLS access denied status=404 requestId=hls-test220 assetId=" + old)
                .doesNotContain(token, "Authorization:", "access_token=");
    }

    @Test
    void currentOrderingUsesUnsignedPostgresUuidTieBreak() throws Exception {
        UUID movie = movie("PUBLISHED");
        UUID a = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        UUID b = UUID.fromString("80000000-0000-0000-0000-000000000000");
        assertThat(a.compareTo(b)).isPositive();
        asset(a, movie, "READY", T0);
        asset(b, movie, "READY", T0);
        String token = bearer("ROLE_USER");
        for (boolean segment : List.of(false, true)) {
            notFound(path(a, segment), token);
            mvc.perform(request(path(b, segment), token)).andExpect(status().isOk());
        }
    }

    @Test
    void badStoredCoordinatesNeverReachStorage(CapturedOutput output) throws Exception {
        UUID id = ready(movie("PUBLISHED"));
        String token = bearer("ROLE_USER");
        for (String bad : List.of("private/key", "video-assets/" + UUID.randomUUID() + "/attempt-2/master.m3u8",
                prefix(id).replace("attempt-2", "attempt-0") + "master.m3u8",
                prefix(id).replace("attempt-2", "attempt-02") + "master.m3u8")) {
            jdbc.update("update video_assets set hls_master_object_key=? where id=?", bad, id);
            for (boolean segment : List.of(false, true)) notFound(path(id, segment), token);
        }
        jdbc.update("update video_assets set hls_master_object_key=?, hls_bucket='foreign-bucket' where id=?", key(id), id);
        notFound(path(id, false), token);
        jdbc.update("update video_assets set hls_master_object_key=null, hls_bucket=null where id=?", id);
        notFound(path(id, true), token);
        verifyNoInteractions(storage);
        assertThat(output.getAll()).contains("WARN", "HLS integrity failure assetId=" + id, "requestId=hls-test220")
                .doesNotContain("foreign-bucket", "private/key", token, key(id));
    }

    @Test
    void malformedIdsAndUnsafeNamesAre404BeforeStorage() throws Exception {
        UUID id = ready(movie("PUBLISHED"));
        String token = bearer("ROLE_USER");
        for (String invalid : List.of("not-a-uuid", "1-1-1-1-1")) {
            notFound("/hls/video-assets/" + invalid + "/master.m3u8", token);
            notFound("/hls/video-assets/" + invalid + "/segments/seg_00001.ts", token);
        }
        for (String name : List.of("seg_1.mp4", "seg_1.ts.bak", ".ts", "seg_1.TS", "seg 1.ts")) {
            notFound("/hls/video-assets/" + id + "/segments/" + name, token);
        }
        verifyNoInteractions(storage);
    }

    @Test
    void missingObjectAndUnavailableStorageHaveSafeContractErrors(CapturedOutput output) throws Exception {
        UUID id = ready(movie("PUBLISHED"));
        String token = bearer("ROLE_USER");
        for (boolean segment : List.of(false, true)) {
            doThrow(new ObjectMissingException()).when(storage).get(anyString(), anyString());
            notFound(path(id, segment), token);
            doThrow(new IOException("internal transport diagnostics")).when(storage).get(anyString(), anyString());
            MvcResult result = mvc.perform(request(path(id, segment), token).header("Accept", "*/*"))
                    .andExpect(status().isServiceUnavailable()).andReturn();
            assertNoStore(result);
            assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("code").asString())
                    .isEqualTo("HLS_STORAGE_UNAVAILABLE");
            assertThat(result.getResponse().getContentAsString()).doesNotContain("internal transport");
        }
        assertThat(output.getAll()).contains("WARN", "HLS integrity failure assetId=" + id, "requestId=hls-test220")
                .doesNotContain(key(id), token, "internal transport diagnostics");
    }

    @Test
    void readFailureBeforeCommitReturnsJson503AndClosesTheStream() throws Exception {
        UUID id = ready(movie("PUBLISHED"));
        when(storage.get(anyString(), anyString())).thenAnswer(call -> new StoredObject(new java.io.InputStream() {
            @Override public int read() throws IOException { throw new IOException("private diagnostics"); }
            @Override public void close() { closed.incrementAndGet(); }
        }, 987654));
        MvcResult result = mvc.perform(request(path(id, false), bearer("ROLE_USER")))
                .andExpect(status().isServiceUnavailable()).andReturn();
        assertNoStore(result);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/json");
        assertThat(result.getResponse().getHeader("Content-Length")).isNotEqualTo("987654");
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("code").asString())
                .isEqualTo("HLS_STORAGE_UNAVAILABLE");
        assertThat(closed).hasValue(1);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"video/mp2t", "application/vnd.apple.mpegurl"})
    void errorsStayJsonForMediaOnlyAndAbsentAccept(String accept) throws Exception {
        UUID id = ready(movie("PUBLISHED"));
        String token = bearer("ROLE_USER");
        for (boolean segment : List.of(false, true)) {
            notFound(request(path(UUID.randomUUID(), segment), token, accept));

            jdbc.update("update video_assets set hls_master_object_key='invalid-master-key' where id=?", id);
            notFound(request(path(id, segment), token, accept));
            jdbc.update("update video_assets set hls_master_object_key=? where id=?", key(id), id);

            doThrow(new ObjectMissingException()).when(storage).get(anyString(), anyString());
            notFound(request(path(id, segment), token, accept));

            doThrow(new IOException("private diagnostics")).when(storage).get(anyString(), anyString());
            assertJsonError(mvc.perform(request(path(id, segment), token, accept)).andReturn(),
                    503, "HLS_STORAGE_UNAVAILABLE");

            doAnswer(call -> new StoredObject(new java.io.InputStream() {
                @Override public int read() throws IOException { throw new IOException("private diagnostics"); }
                @Override public void close() { closed.incrementAndGet(); }
            }, 987654)).when(storage).get(anyString(), anyString());
            int closedBefore = closed.get();
            MvcResult readFailure = mvc.perform(request(path(id, segment), token, accept)).andReturn();
            assertJsonError(readFailure, 503, "HLS_STORAGE_UNAVAILABLE");
            assertThat(readFailure.getResponse().getHeader("Content-Length")).isNotEqualTo("987654");
            assertThat(closed).hasValue(closedBefore + 1);

            assertJsonError(mvc.perform(request(path(id, segment), null, accept)).andReturn(),
                    401, "UNAUTHORIZED");
        }
    }

    private StoredObject object(byte[] body, long size) {
        return new StoredObject(new ByteArrayInputStream(body) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return super.read(b, off, len);
            }
            @Override public void close() { closed.incrementAndGet(); }
        }, size);
    }

    private void assertMedia(MvcResult result, boolean segment, boolean knownSize) {
        var response = result.getResponse();
        assertNoStore(result);
        assertThat(response.getContentType()).isEqualTo(segment ? "video/mp2t" : "application/vnd.apple.mpegurl");
        byte[] bytes = segment ? SEGMENT : PLAYLIST;
        assertThat(response.getContentAsByteArray()).isEqualTo(bytes);
        assertThat(response.getHeader("Content-Length")).isEqualTo(knownSize ? String.valueOf(bytes.length) : null);
        assertThat(response.getHeader("Content-Range")).isNull();
        assertThat(response.getHeader("Accept-Ranges")).isNull();
    }

    private void assertNoStore(MvcResult result) {
        assertThat(result.getResponse().getHeaders("Cache-Control")).containsExactly("no-store");
    }

    private void notFound(String path, String token) throws Exception {
        notFound(request(path, token));
    }

    private void notFound(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andExpect(status().isNotFound()).andReturn();
        assertJsonError(result, 404, "VIDEO_ASSET_NOT_FOUND");
        assertThat(result.getResponse().getHeader("Content-Disposition")).isEqualTo("inline;filename=f.txt");
        ObjectNode body = (ObjectNode) mapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asString()).isEqualTo("VIDEO_ASSET_NOT_FOUND");
        assertThat(body.path("message").asString()).isEqualTo("Video asset not found");
        assertThat(body.path("requestId").asString()).isEqualTo("hls-test220");
        body.remove("timestamp");
        List<String> headers = result.getResponse().getHeaderNames().stream().sorted()
                .map(name -> name + ":" + result.getResponse().getHeaders(name)).toList();
        if (expected404 == null) { expected404 = body; expected404Headers = headers; }
        assertThat(body).isEqualTo(expected404);
        assertThat(headers).isEqualTo(expected404Headers);
    }

    private void assertJsonError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(MediaType.parseMediaType(result.getResponse().getContentType())
                .isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertNoStore(result);
        var body = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("code").asString()).isEqualTo(code);
    }

    private MockHttpServletRequestBuilder request(String path, String token, String accept) {
        var request = request(path, token);
        if (accept != null) request.header(HttpHeaders.ACCEPT, accept);
        return request;
    }

    private MockHttpServletRequestBuilder request(String path, String token) {
        var request = get(path).header("X-Request-ID", "hls-test220");
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return request;
    }
    private static String path(UUID id, boolean segment) {
        return "/hls/video-assets/" + id + (segment ? "/segments/seg_00001.ts" : "/master.m3u8");
    }
    private static String prefix(UUID id) { return "video-assets/" + id + "/attempt-2/"; }
    private static String key(UUID id) { return prefix(id) + "master.m3u8"; }

    private UUID movie(String status) {
        UUID id = UUID.randomUUID();
        movies.add(id);
        jdbc.update("insert into movies(id,title,slug,description,status,created_at,updated_at) values(?,? ,?,'HLS fixture',?,?,?)",
                id, "HLS fixture", "hls-" + id, status, Timestamp.from(T0), Timestamp.from(T0));
        return id;
    }
    private UUID ready(UUID movie) { return asset(UUID.randomUUID(), movie, "READY", T0); }
    private UUID asset(UUID id, UUID movie, String status, Instant created) {
        jdbc.update("insert into video_assets(id,movie_id,raw_bucket,raw_object_key,hls_bucket,hls_master_object_key,status,created_at,updated_at) "
                        + "values(?,?,'vod-raw',?,'vod-hls',?,?,?,?)",
                id, movie, id.toString(), key(id), status, Timestamp.from(created), Timestamp.from(created));
        return id;
    }
    private String bearer(String role) {
        return bearerSessions.token(role);
    }
}
