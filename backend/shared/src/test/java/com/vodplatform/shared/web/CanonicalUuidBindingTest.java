package com.vodplatform.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vodplatform.shared.error.ApiErrorFactory;
import com.vodplatform.shared.error.ApiExceptionHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link CanonicalUuidBinding} with {@link ApiExceptionHandler}: a UUID path variable, request
 * parameter or header that is not in canonical form is a 400 {@code VALIDATION_ERROR} for that
 * parameter.
 */
class CanonicalUuidBindingTest {

    private static final Instant NOW = Instant.parse("2026-10-27T09:15:30Z");
    private static final String SHORT_FORM = "a-b-c-d-e";
    private static final String CANONICAL = "0000000a-000b-000c-000d-00000000000e";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new CanonicalUuidBinding(),
                        new ApiExceptionHandler(new ApiErrorFactory(Clock.fixed(NOW, ZoneOffset.UTC))))
                .build();
    }

    @ParameterizedTest
    @CsvSource({
            "/test/movies/" + CANONICAL,
            "/test/movies/0000000A-000B-000C-000D-00000000000E",
            "/test/movies?genreId=" + CANONICAL
    })
    void bindsTheCanonicalFormInEitherCase(String uri) throws Exception {
        mockMvc.perform(get(uri))
                .andExpect(status().isOk())
                .andExpect(content().string(CANONICAL));
    }

    @ParameterizedTest
    @CsvSource({
            "/test/movies/" + SHORT_FORM + ", movieId",
            "/test/movies/+1-1-1-1-1, movieId",
            "/test/movies?genreId=" + SHORT_FORM + ", genreId"
    })
    void rejectsOtherFormsAsAValidationErrorOfTheParameter(String uri, String field) throws Exception {
        mockMvc.perform(get(uri))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(validationError(field), JsonCompareMode.STRICT));
    }

    @Test
    void rejectsSurroundingWhitespaceInsteadOfTrimmingIt() throws Exception {
        mockMvc.perform(get("/test/movies").param("genreId", " " + CANONICAL + " "))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(validationError("genreId"), JsonCompareMode.STRICT));
    }

    @Test
    void rejectsOtherFormsInHeaders() throws Exception {
        mockMvc.perform(get("/test/headers").header("X-Movie-Id", SHORT_FORM))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(validationError("X-Movie-Id"), JsonCompareMode.STRICT));
    }

    @Test
    void anEmptyOptionalParameterIsAbsent() throws Exception {
        mockMvc.perform(get("/test/movies?genreId="))
                .andExpect(status().isOk())
                .andExpect(content().string("none"));
    }

    private static String validationError(String field) {
        return """
                {
                  "timestamp": "2026-10-27T09:15:30Z",
                  "status": 400,
                  "code": "VALIDATION_ERROR",
                  "message": "Request validation failed",
                  "fieldErrors": [{"field": "%s", "message": "must be a valid UUID"}]
                }
                """.formatted(field);
    }

    @RestController
    static class TestController {

        @GetMapping("/test/movies/{movieId}")
        String movie(@PathVariable UUID movieId) {
            return movieId.toString();
        }

        @GetMapping("/test/movies")
        String movies(@RequestParam(name = "genreId", required = false) UUID genreId) {
            return genreId == null ? "none" : genreId.toString();
        }

        @GetMapping("/test/headers")
        String headers(@RequestHeader("X-Movie-Id") UUID movieId) {
            return movieId.toString();
        }
    }
}
