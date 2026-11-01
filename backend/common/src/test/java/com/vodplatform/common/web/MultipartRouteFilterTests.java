package com.vodplatform.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.vodplatform.shared.error.ApiErrorFactory;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.Part;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

class MultipartRouteFilterTests {

    private static final String UPLOAD =
            "/api/v1/admin/movies/6f1d2a4e-8c3b-4a5f-9e7d-1b2c3d4e5f60/video";

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final MultipartRouteFilter filter =
            new MultipartRouteFilter(
                    new ApiErrorFactory(
                            Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC)),
                    mapper);

    @ParameterizedTest
    @ValueSource(
            strings = {
                "multipart/form-data; boundary=audit",
                "MULTIPART/FORM-DATA; boundary=audit",
                " multipart/mixed ; boundary=audit",
                "multipart/byteranges; boundary=audit"
            })
    void rejectsAllMultipartSubtypesWithoutReadingParametersOrBody(String contentType)
            throws Exception {
        var request = request("GET", "/api/v1/health", contentType);
        request.addHeader("X-Request-ID", "request.id_123");
        var response = rejected(request);
        var json = mapper.readTree(response.getContentAsByteArray());
        assertThat(json.get("timestamp").asString()).isEqualTo("2026-11-01T00:00:00Z");
        assertThat(json.get("status").asInt()).isEqualTo(400);
        assertThat(json.get("code").asString()).isEqualTo("MALFORMED_REQUEST");
        assertThat(json.get("message").asString())
                .isEqualTo("Multipart body is not allowed on this route");
        assertThat(json.get("requestId").asString()).isEqualTo("request.id_123");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"bad\nheader", "bad space", "a/b"})
    void missingOrUnsafeRequestIdIsReplacedWithoutEchoingInput(String id) throws Exception {
        var request = request("GET", "/api/v1/health", "multipart/form-data; boundary=audit");
        if (id != null) {
            request.addHeader("X-Request-ID", id);
        }
        var json = mapper.readTree(rejected(request).getContentAsByteArray());
        assertThat(json.get("requestId").asString()).matches("[a-f0-9-]{36}");
    }

    static Stream<Arguments> deniedOperations() {
        return Stream.of(
                Arguments.of("GET", UPLOAD),
                Arguments.of("PUT", UPLOAD),
                Arguments.of("POST", "/api/v1/movies"),
                Arguments.of("POST", "/api/v1/admin/spool-test"),
                Arguments.of("POST", UPLOAD + "/"),
                Arguments.of("POST", UPLOAD.replace("/admin/", "/admin//")),
                Arguments.of("POST", UPLOAD.replace("/admin/", "/admin;x=y/")),
                Arguments.of("POST", UPLOAD.replace("/admin/", "/admin%2f")),
                Arguments.of("POST", UPLOAD.replace("/admin/", "/admin%2F")),
                Arguments.of("POST", UPLOAD.replace("/admin/", "/admin%5c")),
                Arguments.of("POST", UPLOAD.replace("/admin/", "/admin/../admin/")));
    }

    @ParameterizedTest
    @MethodSource("deniedOperations")
    void wrongMethodAndPathTricksCannotOpenTheUploadException(String method, String path)
            throws Exception {
        rejected(request(method, path, "multipart/form-data; boundary=audit"));
    }

    @Test
    void canonicalUploadWithQueryAndContextPathStillReachesSecurityWithoutParsing()
            throws Exception {
        var request = request("POST", "/vod" + UPLOAD, "multipart/form-data; boundary=audit");
        request.setContextPath("/vod");
        request.setServletPath(UPLOAD);
        request.setQueryString("page=3");
        passes(request);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"application/json", "application/x-www-form-urlencoded"})
    void ordinaryNonMultipartRequestsKeepTheirExistingHandling(String contentType)
            throws Exception {
        passes(request("POST", "/api/v1/auth/login", contentType));
    }

    private MockHttpServletResponse rejected(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(
                noParameterOrBodyReads(request),
                response,
                (req, res) -> {
                    throw new AssertionError("Rejected multipart must not reach the chain");
                });
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/json");
        return response;
    }

    private void passes(MockHttpServletRequest request) throws Exception {
        AtomicBoolean passed = new AtomicBoolean();
        filter.doFilter(
                noParameterOrBodyReads(request),
                new MockHttpServletResponse(),
                (req, res) -> passed.set(true));
        assertThat(passed).isTrue();
    }

    private MockHttpServletRequest request(String method, String path, String contentType) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setContentType(contentType);
        return request;
    }

    private HttpServletRequest noParameterOrBodyReads(MockHttpServletRequest request) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public String getParameter(String name) {
                throw new AssertionError("Parameter read");
            }

            @Override
            public Enumeration<String> getParameterNames() {
                throw new AssertionError("Parameter names read");
            }

            @Override
            public Map<String, String[]> getParameterMap() {
                throw new AssertionError("Parameter map read");
            }

            @Override
            public String[] getParameterValues(String name) {
                throw new AssertionError("Parameter values read");
            }

            @Override
            public Collection<Part> getParts() {
                throw new AssertionError("Parts read");
            }

            @Override
            public Part getPart(String name) {
                throw new AssertionError("Part read");
            }

            @Override
            public BufferedReader getReader() {
                throw new AssertionError("Body reader read");
            }

            @Override
            public ServletInputStream getInputStream() {
                throw new AssertionError("Body read");
            }
        };
    }
}
