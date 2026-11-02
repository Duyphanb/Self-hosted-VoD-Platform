package com.vodplatform.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.vodplatform.shared.error.ApiErrorFactory;
import com.vodplatform.shared.web.RequestIds;

import jakarta.servlet.RequestDispatcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

class CorrelatedErrorAttributesTests {

    private final CorrelatedErrorAttributes attributes =
            new CorrelatedErrorAttributes(
                    new ApiErrorFactory(
                            Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC)),
                    JsonMapper.builder().build());

    private static MockHttpServletRequest errorPage(int status, boolean correlated) {
        var request = new MockHttpServletRequest("POST", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        request.setAttribute(
                RequestDispatcher.ERROR_REQUEST_URI,
                "/api/v1/admin/movies/6f1d2a4e-8c3b-4a5f-9e7d-1b2c3d4e5f60/video");
        if (correlated) {
            request.addHeader(RequestIds.HEADER, "upload.page_1");
            RequestIds.resolve(request);
        }
        return request;
    }

    private Map<String, Object> body(MockHttpServletRequest request) {
        return attributes.getErrorAttributes(
                new ServletWebRequest(request), ErrorAttributeOptions.defaults());
    }

    @ParameterizedTest
    @CsvSource({"400,MALFORMED_REQUEST", "413,PAYLOAD_TOO_LARGE", "500,INTERNAL_ERROR"})
    void correlatedErrorPagesUseTheApiErrorShape(int status, String code) {
        Map<String, Object> body = body(errorPage(status, true));

        assertThat(body)
                .containsEntry("status", status)
                .containsEntry("code", code)
                .containsEntry("requestId", "upload.page_1")
                .containsEntry("timestamp", "2026-11-02T00:00:00Z")
                .containsKey("message")
                .doesNotContainKeys("error", "path", "fieldErrors");
    }

    @Test
    void otherStatusesOfCorrelatedRequestsKeepBootDefaults() {
        assertThat(body(errorPage(408, true))).containsKeys("error", "path").doesNotContainKey("code");
    }

    @Test
    void uncorrelatedErrorPagesKeepBootDefaults() {
        assertThat(body(errorPage(400, false)))
                .containsKeys("timestamp", "status", "error", "path")
                .doesNotContainKeys("code", "requestId");
    }
}
