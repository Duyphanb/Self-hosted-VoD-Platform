package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
            "spring.servlet.multipart.max-file-size=1KB",
            "spring.servlet.multipart.max-request-size=1KB"
        })
@Import(MultipartLifecycleTests.Endpoint.class)
class MultipartLifecycleTests {
    static final Path spool =
            Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "vod-multipart-test-" + java.util.UUID.randomUUID());
    @LocalServerPort int port;

    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder;

    String bearer() {
        var now = java.time.Instant.now();
        String id = java.util.UUID.randomUUID().toString();
        var claims =
                org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                        .subject(id)
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .claim("userId", id)
                        .claim("email", "multipart-test@example.com")
                        .claim("roles", java.util.List.of("ROLE_ADMIN"))
                        .build();
        return "Bearer "
                + jwtEncoder
                        .encode(
                                org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                                        org.springframework.security.oauth2.jwt.JwsHeader.with(
                                                        org.springframework.security.oauth2.jose.jws
                                                                .MacAlgorithm.HS256)
                                                .type("JWT")
                                                .build(),
                                        claims))
                        .getTokenValue();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Files.createDirectories(spool);
        Files.writeString(spool.resolve("upload_stale.tmp"), "stale");
        Files.setLastModifiedTime(
                spool.resolve("upload_stale.tmp"), java.nio.file.attribute.FileTime.fromMillis(1));
        registry.add("spring.servlet.multipart.location", () -> spool.toString());
    }

    @Test
    void startupSweepSuccessOversizeAndCancelledBodyLeaveNoTempFiles() throws Exception {
        assertEmpty();
        String prefix =
                "--audit\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"test.mp4\"\r\n"
                        + "Content-Type: video/mp4\r\n\r\n";
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        for (int length : new int[] {20, 2000}) {
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + port
                                                    + "/api/v1/admin/spool-test"))
                            .timeout(Duration.ofSeconds(5))
                            .header("Authorization", bearer())
                            .header("Content-Type", "multipart/form-data; boundary=audit")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            prefix + "x".repeat(length) + "\r\n--audit--\r\n"))
                            .build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(length == 20 ? 200 : 413);
            assertEmpty();
        }
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream()
                    .write(
                            ("POST /api/v1/admin/spool-test HTTP/1.1\r\n"
                                            + "Host: localhost\r\n"
                                            + "Authorization: "
                                            + bearer()
                                            + "\r\n"
                                            + "Content-Type: multipart/form-data;"
                                            + " boundary=audit\r\n"
                                            + "Content-Length: 900\r\n\r\n"
                                            + prefix
                                            + "partial")
                                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            socket.shutdownOutput();
            socket.setSoTimeout(5000);
            socket.getInputStream().readAllBytes();
        }
        assertEmpty();
    }

    @Test
    void multipartOnPermitAllRouteDoesNotSpoolEvenWhileHandlerRuns() throws Exception {
        String body =
                "--audit\r\nContent-Disposition: form-data; name=\"file\"; filename=\"ignored.mp4\""
                        + "\r\nContent-Type: video/mp4\r\n\r\n"
                        + "x".repeat(2000)
                        + "\r\n--audit--\r\n";
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/health"))
                        .header("Authorization", bearer())
                        .header("Content-Type", "multipart/form-data; boundary=audit")
                        .header("X-Test-No-Parts", "true")
                        .timeout(Duration.ofSeconds(5))
                        .method("GET", HttpRequest.BodyPublishers.ofString(body))
                        .build();
        var response = client.send(request, HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
        assertEmpty();
    }

    @Test
    void unauthorizedUploadIsRejectedBeforeParsing() throws Exception {
        var client = HttpClient.newHttpClient();
        var request =
                HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + "/api/v1/admin/spool-test"))
                        .header("Content-Type", "multipart/form-data; boundary=audit")
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString("x".repeat(2000)))
                        .build();
        assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(401);
        assertEmpty();
    }

    void assertEmpty() throws Exception {
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

    @TestConfiguration(proxyBeanMethods = false)
    static class Endpoint {
        @Bean
        org.springframework.context.SmartLifecycle beforeConnector() {
            return new org.springframework.context.SmartLifecycle() {
                boolean running;

                public void start() {
                    if (!Files.isDirectory(spool)
                            || Files.exists(spool.resolve("upload_stale.tmp"))) {
                        throw new IllegalStateException(
                                "Spool must be prepared before connector starts");
                    }
                    running = true;
                }

                public void stop() {
                    running = false;
                }

                public boolean isRunning() {
                    return running;
                }

                public int getPhase() {
                    return Integer.MAX_VALUE - 2049;
                }
            };
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
        @GetMapping(value = "/api/v1/health", headers = "X-Test-No-Parts=true")
        String noParts() throws IOException {
            try (var files = Files.list(spool)) {
                assertThat(files.toList()).isEmpty();
            }
            return "ok";
        }

        @PostMapping("/api/v1/admin/spool-test")
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
        @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
        @ResponseStatus(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE)
        void tooLarge() {}
    }
}
