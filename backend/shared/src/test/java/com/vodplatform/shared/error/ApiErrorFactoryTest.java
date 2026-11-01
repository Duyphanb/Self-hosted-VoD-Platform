package com.vodplatform.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vodplatform.shared.web.RequestIds;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

class ApiErrorFactoryTest {

    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    private final ApiErrorFactory factory = new ApiErrorFactory(Clock.fixed(NOW, ZoneOffset.UTC));
    private final Logger logger = (Logger) LoggerFactory.getLogger(ApiErrorFactory.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void reset() {
        logger.detachAppender(logs);
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void uncorrelatedErrorsKeepTheirBodyAndAreNotLogged() {
        ApiError error = factory.create(HttpStatus.NOT_FOUND, "MOVIE_NOT_FOUND", "Movie not found", List.of());

        assertThat(error).isEqualTo(new ApiError(NOW, 404, "MOVIE_NOT_FOUND", "Movie not found", List.of()));
        assertThat(JsonMapper.builder().build().writeValueAsString(error)).doesNotContain("requestId");
        assertThat(logs.list).isEmpty();
    }

    @Test
    void correlatedErrorsCarryTheIdAndAreLoggedOnceByStatusClass() {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIds.HEADER, "upload.req_1");
        RequestIds.resolve(request);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        ApiError notFound = factory.create(HttpStatus.NOT_FOUND, "MOVIE_NOT_FOUND", "Movie not found", List.of());
        ApiError failed = factory.create(HttpStatus.SERVICE_UNAVAILABLE, "UPLOAD_STORAGE_UNAVAILABLE", "x", List.of());

        assertThat(notFound.requestId()).isEqualTo("upload.req_1");
        assertThat(failed.requestId()).isEqualTo("upload.req_1");
        assertThat(logs.list).hasSize(2);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(logs.list.get(0).getFormattedMessage())
                .isEqualTo("api request rejected status=404 code=MOVIE_NOT_FOUND requestId=upload.req_1");
        assertThat(logs.list.get(1).getLevel()).isEqualTo(Level.WARN);
        assertThat(logs.list.get(1).getFormattedMessage())
                .isEqualTo("api request rejected status=503 code=UPLOAD_STORAGE_UNAVAILABLE requestId=upload.req_1");
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    @Test
    void anExplicitIdIsUsedWithoutARequestContext() {
        ApiError error = factory.create(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "x", List.of(), "abc");

        assertThat(error.requestId()).isEqualTo("abc");
        assertThat(JsonMapper.builder().build().writeValueAsString(error)).contains("\"requestId\":\"abc\"");
    }
}
