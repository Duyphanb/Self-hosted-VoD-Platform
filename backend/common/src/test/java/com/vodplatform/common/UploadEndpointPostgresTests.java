package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vodplatform.common.contract.ApiContract;
import com.vodplatform.common.support.PostgresTestDatabase;
import com.vodplatform.encoding.messaging.EncodingJobPublisher;
import com.vodplatform.encoding.messaging.PublishOutcome;
import com.vodplatform.shared.storage.ObjectStorage;

import jakarta.servlet.MultipartConfigElement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * HTTP-level race on the shared test PostgreSQL ({@link PostgresTestDatabase}): two parallel uploads
 * for one movie both pass the advisory pre-check and store raw media; the per-movie lock and
 * in-lock recheck then admit one pair (the V5 index translation itself is covered by
 * {@link UploadPostgresTests}). The winner gets 202, the loser a contract 409 with its requestId,
 * and only the loser's raw key is deleted. Storage and the broker are mocked.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "auth.tokens.secret=test-only-secret-with-at-least-32-bytes")
class UploadEndpointPostgresTests implements PostgresTestDatabase {

    private static final String PREFIX =
            "--race\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"race.mp4\"\r\n"
                    + "Content-Type: video/mp4\r\n\r\n";

    @LocalServerPort int port;

    @Autowired JdbcTemplate jdbc;

    @Autowired JwtEncoder jwtEncoder;

    @Autowired ObjectMapper mapper;

    @Autowired MultipartConfigElement multipart;

    @MockitoBean ObjectStorage storage;

    @MockitoBean EncodingJobPublisher publisher;

    private UUID movie;

    @BeforeEach
    void fixture() {
        when(publisher.confirmTimeout()).thenReturn(Duration.ofSeconds(5));
        when(publisher.publish(any(), any())).thenReturn(new PublishOutcome.Confirmed());
        movie = UUID.randomUUID();
        jdbc.update(
                "insert into movies (id,title,slug,description,status,created_at,updated_at)"
                        + " values (?,?,?,?,?,?,?)",
                movie,
                "Upload race fixture",
                "upload-race-" + movie,
                "fixture",
                "DRAFT",
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
    }

    @AfterEach
    void cleanOwnRows() {
        jdbc.update(
                "delete from encoding_jobs where video_asset_id in "
                        + "(select id from video_assets where movie_id=?)",
                movie);
        jdbc.update("delete from video_assets where movie_id=?", movie);
        jdbc.update("delete from movies where id=?", movie);
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
                        .claim("email", "race-admin@example.com")
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

    @Test
    void parallelHttpUploadsAcceptOneAndRejectTheOtherWithCompensation() throws Exception {
        List<String> keys = new CopyOnWriteArrayList<>();
        CountDownLatch bothStored = new CountDownLatch(2);
        doAnswer(
                        call -> {
                            keys.add(call.getArgument(1));
                            ((InputStream) call.getArgument(2)).readAllBytes();
                            bothStored.countDown();
                            assertThat(bothStored.await(15, TimeUnit.SECONDS)).isTrue();
                            return null;
                        })
                .when(storage)
                .put(anyString(), anyString(), any(), anyLong(), anyString());
        String path = "/api/v1/admin/movies/" + movie + "/video";
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var body = PREFIX + "0000ftypxxxx\r\n--race--\r\n";
        List<HttpResponse<String>> responses;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var requests =
                    List.of("race.a", "race.b").stream()
                            .map(
                                    id ->
                                            HttpRequest.newBuilder(
                                                            URI.create("http://127.0.0.1:" + port + path))
                                                    .timeout(Duration.ofSeconds(30))
                                                    .header("Authorization", admin())
                                                    .header("X-Request-ID", id)
                                                    .header("Content-Type", "multipart/form-data; boundary=race")
                                                    .POST(HttpRequest.BodyPublishers.ofString(body))
                                                    .build())
                            .toList();
            var futures =
                    requests.stream()
                            .map(
                                    request ->
                                            pool.submit(
                                                    () ->
                                                            client.send(
                                                                    request,
                                                                    HttpResponse.BodyHandlers.ofString())))
                            .toList();
            responses = List.of(futures.get(0).get(40, TimeUnit.SECONDS), futures.get(1).get(40, TimeUnit.SECONDS));
        }

        var accepted = responses.stream().filter(response -> response.statusCode() == 202).toList();
        var rejected = responses.stream().filter(response -> response.statusCode() == 409).toList();
        assertThat(accepted).hasSize(1);
        assertThat(rejected).hasSize(1);
        for (var response : responses) {
            assertThat(
                            ApiContract.get()
                                    .violations(
                                            "POST",
                                            path,
                                            response.statusCode(),
                                            response.headers().firstValue("content-type").orElse(null),
                                            response.body().getBytes(StandardCharsets.UTF_8)))
                    .isEmpty();
        }
        var conflict = mapper.readTree(rejected.get(0).body());
        assertThat(conflict.get("code").asString()).isEqualTo("VIDEO_UPLOAD_IN_PROGRESS");
        assertThat(conflict.get("requestId").asString()).isIn("race.a", "race.b");

        String winner = mapper.readTree(accepted.get(0).body()).get("videoAssetId").asString();
        assertThat(keys).hasSize(2);
        String loserKey = keys.stream().filter(key -> !key.contains(winner)).findFirst().orElseThrow();
        verify(storage, times(1)).delete(anyString(), anyString());
        verify(storage).delete(eq("vod-raw"), eq(loserKey));
        assertThat(jdbc.queryForObject("select count(*) from video_assets where movie_id=?", Integer.class, movie))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from encoding_jobs j join video_assets a on a.id=j.video_asset_id"
                                + " where a.movie_id=?",
                        Integer.class,
                        movie))
                .isEqualTo(1);

        Path spool = Path.of(multipart.getLocation());
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline && hasSpoolFiles(spool)) {
            Thread.sleep(20);
        }
        assertThat(hasSpoolFiles(spool)).as("spool released after both requests").isFalse();
    }

    private static boolean hasSpoolFiles(Path spool) throws Exception {
        try (var files = Files.list(spool)) {
            return files.anyMatch(file -> file.getFileName().toString().startsWith("upload_"));
        }
    }
}
