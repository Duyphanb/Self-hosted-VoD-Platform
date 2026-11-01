package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.vodplatform.common.web.MultipartRouteFilter;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;

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
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
            "spring.servlet.multipart.max-file-size=1KB",
            "spring.servlet.multipart.max-request-size=1KB"
        })
@Import(MultipartLifecycleTests.Endpoint.class)
class MultipartLifecycleTests {

    private static final String UPLOAD =
            "/api/v1/admin/movies/6f1d2a4e-8c3b-4a5f-9e7d-1b2c3d4e5f60/video";

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

    @LocalServerPort int port;

    @Autowired JwtEncoder jwtEncoder;

    @Autowired ObjectMapper mapper;

    @Autowired ApplicationContext context;

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
        assertEmpty();
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

    private String body(int size) {
        return PREFIX + "x".repeat(size) + "\r\n--audit--\r\n";
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5));
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

    @ParameterizedTest
    @ValueSource(ints = {20, 2000})
    void authorizedUploadSpoolsAndCleansUpAfterSuccessOrOversize(int length) throws Exception {
        var request =
                request(UPLOAD)
                        .header("Authorization", bearer("ROLE_ADMIN"))
                        .header("Content-Type", CONTENT_TYPE)
                        .POST(HttpRequest.BodyPublishers.ofString(body(length)))
                        .build();
        var response = client.send(request, HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(length == 20 ? 200 : 413);
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
            // The test-only MultipartException advice returns 400 only AFTER bearer authorization
            // and attempted multipart parsing. A rejected token (401/403) cannot make cleanup pass.
            assertThat(response.lines().findFirst())
                    .hasValueSatisfying(status -> assertThat(status).startsWith("HTTP/1.1 400"));
        }
        assertEmpty();
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
        var builder =
                request(UPLOAD)
                        .header("Content-Type", CONTENT_TYPE)
                        .POST(HttpRequest.BodyPublishers.ofString(body(700)));
        if (!role.equals("anonymous")) {
            builder.header("Authorization", bearer(role));
        }
        assertNoSpool(
                () -> {
                    var response =
                            client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode())
                            .isEqualTo(role.equals("anonymous") ? 401 : 403);
                });
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

        @Bean
        TestErrors testErrors() {
            return new TestErrors();
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

        // Test-only stand-in for #174's real controller. Replace this probe when that endpoint
        // arrives; keep the production gate and the upload authorization-before-parse assertions.
        @PostMapping(UPLOAD)
        int receive(@RequestPart("file") MultipartFile file) throws Exception {
            try (var files = Files.list(spool)) {
                assertThat(
                                files.anyMatch(
                                        path ->
                                                path.getFileName()
                                                        .toString()
                                                        .startsWith("upload_")))
                        .isTrue();
            }
            try (var input = file.getInputStream()) {
                return input.readAllBytes().length;
            }
        }
    }

    @RestControllerAdvice
    static class TestErrors {

        @ExceptionHandler(MaxUploadSizeExceededException.class)
        @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
        void tooLarge() {}

        @ExceptionHandler(MultipartException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        void cancelledBody() {}
    }
}
