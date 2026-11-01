package com.vodplatform.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class RequestIdsTest {

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "upload.req_1-A", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"})
    void safeInboundIdsAreReused(String id) {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIds.HEADER, id);

        assertThat(RequestIds.resolve(request)).isEqualTo(id);
        assertThat(RequestIds.existing(request)).contains(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "with space",
            "slash/x",
            "line\nbreak",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0"
    })
    void unsafeInboundIdsAreReplacedNotEchoed(String id) {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIds.HEADER, id);

        String resolved = RequestIds.resolve(request);

        assertThat(resolved).isNotEqualTo(id);
        assertThat(UUID.fromString(resolved)).hasToString(resolved);
    }

    @Test
    void anIdIsResolvedOncePerRequest() {
        var request = new MockHttpServletRequest();

        String first = RequestIds.resolve(request);
        request.addHeader(RequestIds.HEADER, "later");

        assertThat(RequestIds.resolve(request)).isEqualTo(first);
    }

    @Test
    void uncorrelatedRequestsHaveNoId() {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIds.HEADER, "not-resolved");

        assertThat(RequestIds.existing(request)).isEmpty();
        assertThat(RequestIds.current()).isEmpty();
    }

    @Test
    void currentReadsTheRequestBoundToTheThread() {
        var request = new MockHttpServletRequest();
        String id = RequestIds.resolve(request);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(RequestIds.current()).contains(id);
    }

    @Test
    void sanitizeKeepsOnlySafeTokens() {
        assertThat(RequestIds.sanitize("abc.DEF_1-2")).contains("abc.DEF_1-2");
        assertThat(RequestIds.sanitize("abc def")).isEmpty();
        assertThat(RequestIds.sanitize(null)).isEmpty();
    }
}
