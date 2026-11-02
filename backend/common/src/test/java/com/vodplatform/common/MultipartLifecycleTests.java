package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vodplatform.common.contract.ApiContract;
import com.vodplatform.common.web.MultipartRouteFilter;
import com.vodplatform.encoding.messaging.EncodingJobPublisher;
import com.vodplatform.encoding.messaging.PublishOutcome;
import com.vodplatform.shared.storage.ObjectStorage;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;

import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.attribute.FileTime;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Real-Tomcat multipart lifecycle of the production upload operation: the route gate, authorization
 * before parsing, the spool lifecycle, the operation's error mappings and requestId, and the
 * connector idle timeout. Storage and the broker are mocked; the database is the test H2.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
            "spring.servlet.multipart.max-file-size=8KB",
            "spring.servlet.multipart.max-request-size=8KB"
        })
@Import(MultipartLifecycleTests.Endpoint.class)
class MultipartLifecycleTests {

    private static final UUID MOVIE = UUID.fromString("6f1d2a4e-8c3b-4a5f-9e7d-1b2c3d4e5f60");

    private static final String UPLOAD = "/api/v1/admin/movies/" + MOVIE + "/video";

    private static final String PARAMETER_PROBE = "X-Test-Read-Parameters";

    private static final String CONTENT_TYPE = "multipart/form-data; boundary=audit";

    private static final String PREFIX =
            "--audit\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"test.mp4\"\r\n"
                    + "Content-Type: video/mp4\r\n\r\n";

    private static final Path spool =
            Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "vod-multipart-test-" + UUID.randomUUID());

    private static final AtomicInteger earlyParameterReads = new AtomicInteger();

    private static final AtomicInteger parameterHandlerCalls = new AtomicInteger();

    private final AtomicBoolean spoolSeenDuringPut = new AtomicBoolean();

    @LocalServerPort int port;

    @Autowired JwtEncoder jwtEncoder;

    @Autowired ObjectMapper mapper;

    @Autowired ApplicationContext context;

    @Autowired JdbcTemplate jdbc;

    @MockitoBean ObjectStorage storage;

    @MockitoBean EncodingJobPublisher publisher;

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Files.createDirectories(spool);
        Files.writeString(spool.resolve("upload_stale.tmp"), "stale");
        Files.setLastModifiedTime(spool.resolve("upload_stale.tmp"), FileTime.fromMillis(1));
        registry.add("spring.servlet.multipart.location", spool::toString);
    }

    @BeforeEach
    void resetProbes() throws Exception {
        earlyParameterReads.set(0);
        parameterHandlerCalls.set(0);
        spoolSeenDuringPut.set(false);
        assertEmpty();
        Integer movies = jdbc.queryForObject("select count(*) from movies where id=?", Integer.class, MOVIE);
        if (movies == null || movies == 0) {
            jdbc.update(
                    "insert into movies (id,title,slug,description,status,created_at,updated_at)"
                            + " values (?,?,?,?,?,?,?)",
                    MOVIE,
                    "Multipart fixture",
                    "multipart-" + MOVIE,
                    "fixture",
                    "DRAFT",
                    Timestamp.from(Instant.now()),
                    Timestamp.from(Instant.now()));
        }
        when(publisher.confirmTimeout()).thenReturn(Duration.ofSeconds(5));
        when(publisher.publish(any(), any())).thenReturn(new PublishOutcome.Confirmed());
        doAnswer(
                        call -> {
                            try (var files = Files.list(spool)) {
                                spoolSeenDuringPut.set(
                                        files.anyMatch(
                                                path ->
                                                        path.getFileName()
                                                                .toString()
                                                                .startsWith("upload_")));
                            }
                            ((InputStream) call.getArgument(2)).readAllBytes();
                            return null;
                        })
                .when(storage)
                .put(anyString(), anyString(), any(), anyLong(), anyString());
    }

    @AfterEach
    void removeUploads() {
        jdbc.update(
                "delete from encoding_jobs where video_asset_id in "
                        + "(select id from video_assets where movie_id=?)",
                MOVIE);
        jdbc.update("delete from video_assets where movie_id=?", MOVIE);
    }

    private String bearer(String role) {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        var claims =
                JwtClaimsSet.builder()
                        .subject(id)
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .claim("userId", id)
                        .claim("email", "multipart-test@example.com")
                        .claim("roles", List.of(role))
                        .build();
        return "Bearer "
                + jwtEncoder
                        .encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                                        claims))
                        .getTokenValue();
    }

    /** A multipart body whose file has a valid ISO-BMFF signature ({@code ftyp} at offset 4). */
    private String body(int size) {
        return PREFIX + "0000ftyp" + "x".repeat(size - 8) + "\r\n--audit--\r\n";
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5));
    }

    private HttpResponse<String> upload(String path, String requestId, String payload)
            throws Exception {
        var builder =
                request(path)
                        .header("Authorization", bearer("ROLE_ADMIN"))
                        .header("Content-Type", CONTENT_TYPE)
                        .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (requestId != null) {
            builder.header("X-Request-ID", requestId);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode contractError(
            String path, HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(
                        ApiContract.get()
                                .violations(
                                        "POST",
                                        path,
                                        response.statusCode(),
                                        response.headers().firstValue("content-type").orElse(null),
                                        response.body().getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
        JsonNode error = mapper.readTree(response.body());
        assertThat(error.get("status").asInt()).isEqualTo(status);
        assertThat(error.get("code").asString()).isEqualTo(code);
        assertThat(response.body())
                .doesNotContain("vod-raw", "minio", "silo", "Exception", "upload_");
        return error;
    }

    @Test
    void gateRunsAfterEncodingAndBeforeParameterReadersAndSecurity() {
        var registration = context.getBean("multipartRouteFilter", FilterRegistrationBean.class);
        assertThat(registration.getFilter()).isInstanceOf(MultipartRouteFilter.class);
        int order = registration.getOrder();
        assertThat(order).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
        assertThat(((Ordered) context.getBean("characterEncodingFilter")).getOrder())
                .isLessThan(order);
        for (String bean :
                List.of(
                        "formContentFilter",
                        "requestContextFilter",
                        "securityFilterChainRegistration")) {
            assertThat(order).isLessThan(((Ordered) context.getBean(bean)).getOrder());
        }
    }

    @Test
    void connectorIdleTimeoutMatchesTheEdgeAndIsNotAnUploadDeadline() {
        var server = ((WebServerApplicationContext) context).getWebServer();
        Connector connector = ((TomcatWebServer) server).getTomcat().getConnector();
        assertThat(connector.getProperty("connectionTimeout")).isEqualTo(60000);
        assertThat(connector.getProperty("disableUploadTimeout")).isEqualTo(true);
    }

    @Test
    void authorizedUploadIsAcceptedWhileTheSpoolIsHeldAndThenCleanedUp() throws Exception {
        var response = upload(UPLOAD, "upload.accepted_1", body(20));
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(
                        ApiContract.get()
                                .violations(
                                        "POST",
                                        UPLOAD,
                                        202,
                                        response.headers().firstValue("content-type").orElse(null),
                                        response.body().getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
        JsonNode accepted = mapper.readTree(response.body());
        assertThat(accepted.get("status").asString()).isEqualTo("QUEUED");
        assertThat(UUID.fromString(accepted.get("videoAssetId").asString())).isNotNull();
        assertThat(spoolSeenDuringPut).as("spool file exists while the raw object streams").isTrue();
        assertEmpty();
    }

    @Test
    void oversizeUploadIsContractErrorWithRequestIdAndCleanedUp() throws Exception {
        var error =
                contractError(
                        UPLOAD,
                        upload(UPLOAD, "upload.too_large_1", body(9000)),
                        413,
                        "PAYLOAD_TOO_LARGE");
        assertThat(error.get("requestId").asString()).isEqualTo("upload.too_large_1");
        assertEmpty();
    }

    @Test
    void cancelledUploadPassesAuthenticationAndCleansUpAfterMultipartFailure() throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream()
                    .write(
                            ("POST "
                                            + UPLOAD
                                            + " HTTP/1.1\r\nHost: localhost\r\n"
                                            + "Authorization: "
                                            + bearer("ROLE_ADMIN")
                                            + "\r\nContent-Type: "
                                            + CONTENT_TYPE
                                            + "\r\nContent-Length: 900\r\n\r\n"
                                            + PREFIX
                                            + "partial")
                                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            socket.shutdownOutput();
            String response =
                    new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            // 400 comes only AFTER bearer authorization and the attempted multipart parse. A
            // rejected token (401/403) cannot make the cleanup assertion pass vacuously.
            assertThat(response.lines().findFirst())
                    .hasValueSatisfying(status -> assertThat(status).startsWith("HTTP/1.1 400"));
        }
        assertEmpty();
    }

    @Test
    void malformedMultipartBodyIsClientError() throws Exception {
        String unterminated = PREFIX + "0000ftypxxxx";
        var error =
                contractError(
                        UPLOAD,
                        upload(UPLOAD, "upload.malformed_1", unterminated),
                        400,
                        "MALFORMED_REQUEST");
        assertThat(error.get("requestId").asString()).isEqualTo("upload.malformed_1");
        assertEmpty();
    }

    @Test
    void tooManyPartsIsPayloadTooLargeBeforeAnythingIsStored() throws Exception {
        StringBuilder parts = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            parts.append("--audit\r\nContent-Disposition: form-data; name=\"p")
                    .append(i)
                    .append("\"\r\n\r\nv\r\n");
        }
        parts.append("--audit--\r\n");
        var error =
                contractError(
                        UPLOAD,
                        upload(UPLOAD, "upload.parts_1", parts.toString()),
                        413,
                        "PAYLOAD_TOO_LARGE");
        assertThat(error.get("requestId").asString()).isEqualTo("upload.parts_1");
        verifyNoInteractions(storage);
        assertEmpty();
    }

    @Test
    void longNonAsciiFileNameIsAccepted() throws Exception {
        String name = "Phim tài liệu Đất Rừng Phương Nam ".repeat(12) + ".mp4";
        String encoded =
                java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        String payload =
                "--audit\r\nContent-Disposition: form-data; name=\"file\"; filename*=UTF-8''"
                        + encoded
                        + "\r\nContent-Type: video/mp4\r\n\r\n0000ftypxxxx\r\n--audit--\r\n";
        // Over Boot's 512B part-header default, under the configured 4KB.
        assertThat(encoded.length()).isGreaterThan(1024);
        assertThat(upload(UPLOAD, null, payload).statusCode()).isEqualTo(202);
        assertEmpty();
    }

    @Test
    void malformedChunkedBodyIsAClientErrorNotStorage() throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream()
                    .write(
                            ("POST "
                                            + UPLOAD
                                            + " HTTP/1.1\r\nHost: localhost\r\n"
                                            + "Authorization: "
                                            + bearer("ROLE_ADMIN")
                                            + "\r\nContent-Type: "
                                            + CONTENT_TYPE
                                            + "\r\nTransfer-Encoding: chunked\r\n\r\n"
                                            + Integer.toHexString(PREFIX.length())
                                            + "\r\n"
                                            + PREFIX
                                            + "\r\nzz\r\n")
                                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String response =
                    new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertThat(response.lines().findFirst())
                    .hasValueSatisfying(status -> assertThat(status).startsWith("HTTP/1.1 400"));
        }
        verifyNoInteractions(storage);
        assertEmpty();
    }

    @Test
    void serverSideSpoolFailureIsStorageUnavailableAndStoresNothing() throws Exception {
        Files.delete(spool);
        Files.writeString(spool, "not a directory");
        try {
            var error =
                    contractError(
                            UPLOAD,
                            upload(UPLOAD, "upload.spool_1", body(20)),
                            503,
                            "UPLOAD_STORAGE_UNAVAILABLE");
            assertThat(error.get("requestId").asString()).isEqualTo("upload.spool_1");
        } finally {
            Files.delete(spool);
            Files.createDirectories(spool);
        }
        Integer assets =
                jdbc.queryForObject(
                        "select count(*) from video_assets where movie_id=?", Integer.class, MOVIE);
        assertThat(assets).isZero();
    }

    @Test
    void additionalPartIsRejectedBeforeAnythingIsStored() throws Exception {
        String twoParts =
                PREFIX
                        + "0000ftypxxxx\r\n--audit\r\n"
                        + "Content-Disposition: form-data; name=\"note\"\r\n\r\nhello"
                        + "\r\n--audit--\r\n";
        var error =
                contractError(
                        UPLOAD, upload(UPLOAD, null, twoParts), 400, "VALIDATION_ERROR");
        assertThat(error.get("fieldErrors").get(0).get("field").asString()).isEqualTo("file");
        assertThat(error.get("fieldErrors").get(0).get("message").asString())
                .isEqualTo("must be the only part");
        assertThat(UUID.fromString(error.get("requestId").asString())).isNotNull();
        verifyNoInteractions(storage);
        assertEmpty();
    }

    @Test
    void nonMultipartBodyIsAMissingFile() throws Exception {
        var request =
                request(UPLOAD)
                        .header("Authorization", bearer("ROLE_ADMIN"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build();
        var error =
                contractError(
                        UPLOAD,
                        client.send(request, HttpResponse.BodyHandlers.ofString()),
                        400,
                        "VALIDATION_ERROR");
        assertThat(error.get("fieldErrors").get(0).get("message").asString())
                .isEqualTo("must not be empty");
    }

    @Test
    void missingMovieIsNotFoundAfterValidationAndCleanedUp() throws Exception {
        String path = "/api/v1/admin/movies/" + UUID.randomUUID() + "/video";
        var error = contractError(path, upload(path, "upload.missing_1", body(20)), 404, "MOVIE_NOT_FOUND");
        assertThat(error.get("requestId").asString()).isEqualTo("upload.missing_1");
        assertEmpty();
    }

    @Test
    void invalidMovieIdIsRejectedBeforeParsing() throws Exception {
        String path = "/api/v1/admin/movies/not-a-uuid/video";
        assertNoSpool(
                () -> {
                    var error =
                            contractError(
                                    path,
                                    upload(path, "upload.bad_id_1", body(700)),
                                    400,
                                    "VALIDATION_ERROR");
                    assertThat(error.get("fieldErrors").get(0).get("field").asString())
                            .isEqualTo("movieId");
                    assertThat(error.get("requestId").asString()).isEqualTo("upload.bad_id_1");
                });
    }

    static Stream<Arguments> nonUploadRequests() {
        return Stream.of(
                Arguments.of("GET", "/api/v1/health?page=3", false),
                Arguments.of("GET", "/api/v1/me/multipart-probe?page=3", true),
                Arguments.of("GET", "/api/v1/movies?page=3", false),
                Arguments.of("PUT", UPLOAD + "?page=3", true));
    }

    @ParameterizedTest
    @MethodSource("nonUploadRequests")
    void multipartCannotReachAnyNonUploadParameterReader(
            String method, String path, boolean authenticated) throws Exception {
        var builder =
                request(path)
                        .header("Content-Type", CONTENT_TYPE)
                        .header(PARAMETER_PROBE, "true")
                        .header("X-Request-ID", "multipart.test_123")
                        .method(method, HttpRequest.BodyPublishers.ofString(body(700)));
        if (authenticated) {
            builder.header("Authorization", bearer("ROLE_ADMIN"));
        }
        assertNoSpool(
                () -> {
                    var response =
                            client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).isEqualTo(400);
                    assertThat(response.headers().firstValue("content-type"))
                            .hasValueSatisfying(
                                    value -> assertThat(value).startsWith("application/json"));
                    var error = mapper.readTree(response.body());
                    assertThat(error.get("status").asInt()).isEqualTo(400);
                    assertThat(error.get("code").asString()).isEqualTo("MALFORMED_REQUEST");
                    assertThat(error.get("requestId").asString()).isEqualTo("multipart.test_123");
                });
        assertThat(earlyParameterReads).hasValue(0);
        assertThat(parameterHandlerCalls).hasValue(0);
    }

    @Test
    void ordinaryQueryParameterRequestStillReachesThePublicHandler() throws Exception {
        var request =
                request("/api/v1/health?page=3").header(PARAMETER_PROBE, "true").GET().build();
        assertNoSpool(
                () -> {
                    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.body()).isEqualTo("page=3");
                });
        assertThat(earlyParameterReads).hasValue(1);
        assertThat(parameterHandlerCalls).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "ROLE_USER"})
    void wellFormedUnauthorizedUploadIsRejectedBeforeAnySpooling(String role) throws Exception {
        for (String path : List.of(UPLOAD, "/api/v1/admin/movies/" + UUID.randomUUID() + "/video")) {
            var builder =
                    request(path)
                            .header("Content-Type", CONTENT_TYPE)
                            .header("X-Request-ID", "upload.denied_1")
                            .POST(HttpRequest.BodyPublishers.ofString(body(700)));
            if (!role.equals("anonymous")) {
                builder.header("Authorization", bearer(role));
            }
            assertNoSpool(
                    () -> {
                        var response =
                                client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                        boolean anonymous = role.equals("anonymous");
                        var error =
                                contractError(
                                        path,
                                        response,
                                        anonymous ? 401 : 403,
                                        anonymous ? "UNAUTHORIZED" : "FORBIDDEN");
                        assertThat(error.get("requestId").asString()).isEqualTo("upload.denied_1");
                    });
        }
    }

    static Stream<String> uploadPathTricks() {
        return Stream.of(
                UPLOAD + "/",
                UPLOAD + ";p=x",
                UPLOAD + "%2fother",
                UPLOAD.replace("/admin/", "/admin//"),
                UPLOAD.replace("/admin/", "/admin;p=x/"),
                UPLOAD.replace("/admin/", "/admin/../admin/"));
    }

    @ParameterizedTest
    @MethodSource("uploadPathTricks")
    void uploadPathTricksDoNotReachTheMultipartHandler(String path) throws Exception {
        var request =
                request(path)
                        .header("Authorization", bearer("ROLE_ADMIN"))
                        .header("Content-Type", CONTENT_TYPE)
                        .POST(HttpRequest.BodyPublishers.ofString(body(700)))
                        .build();
        assertNoSpool(
                () ->
                        assertThat(
                                        client.send(request, HttpResponse.BodyHandlers.discarding())
                                                .statusCode())
                                .isEqualTo(400));
    }

    private void assertNoSpool(CheckedRequest request) throws Exception {
        try (var watcher = FileSystems.getDefault().newWatchService()) {
            spool.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            request.run();
            assertThat(watcher.poll(200, TimeUnit.MILLISECONDS))
                    .as("no transient spool creation")
                    .isNull();
            assertEmpty();
        }
    }

    private void assertEmpty() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        do {
            try (var files = Files.list(spool)) {
                if (files.findAny().isEmpty()) {
                    return;
                }
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        try (var files = Files.list(spool)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @FunctionalInterface
    interface CheckedRequest {
        void run() throws Exception;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Endpoint {

        @Bean
        SmartLifecycle beforeConnector() {
            return new SmartLifecycle() {
                private boolean running;

                @Override
                public void start() {
                    if (!Files.isDirectory(spool)
                            || Files.exists(spool.resolve("upload_stale.tmp"))) {
                        throw new IllegalStateException(
                                "Spool must be prepared before connector starts");
                    }
                    running = true;
                }

                @Override
                public void stop() {
                    running = false;
                }

                @Override
                public boolean isRunning() {
                    return running;
                }

                @Override
                public int getPhase() {
                    return Integer.MAX_VALUE - 2049;
                }
            };
        }

        @Bean
        FilterRegistrationBean<Filter> parameterReadingProbe() {
            Filter reader =
                    (request, response, chain) -> {
                        if ("true"
                                .equals(
                                        ((HttpServletRequest) request)
                                                .getHeader(PARAMETER_PROBE))) {
                            earlyParameterReads.incrementAndGet();
                            request.getParameter("page");
                        }
                        chain.doFilter(request, response);
                    };
            var registration = new FilterRegistrationBean<>(reader);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
            return registration;
        }

        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    @RestController
    static class TestController {

        @GetMapping(
                value = {"/api/v1/health", "/api/v1/me/multipart-probe"},
                headers = "X-Test-Read-Parameters=true")
        String readsParameters(@RequestParam("page") int page) {
            parameterHandlerCalls.incrementAndGet();
            return "page=" + page;
        }
    }
}
