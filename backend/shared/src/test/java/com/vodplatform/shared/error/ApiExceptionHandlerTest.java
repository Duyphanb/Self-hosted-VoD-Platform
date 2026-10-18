package com.vodplatform.shared.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-18T09:15:30Z");
    private static final String MALFORMED_REQUEST_BODY = """
            {
              "timestamp": "2026-10-18T09:15:30Z",
              "status": 400,
              "code": "MALFORMED_REQUEST",
              "message": "Request body is missing or malformed"
            }
            """;

    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new ApiExceptionHandler(new ApiErrorFactory(Clock.fixed(NOW, ZoneOffset.UTC))))
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void closeValidatorFactory() {
        validator.close();
    }

    @Test
    void mapsApiExceptionToItsStatusCodeMessageAndFieldErrors() throws Exception {
        mockMvc.perform(post("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "timestamp": "2026-10-18T09:15:30Z",
                          "status": 409,
                          "code": "MOVIE_SLUG_CONFLICT",
                          "message": "Movie slug already exists",
                          "fieldErrors": [
                            {"field": "slug", "message": "must be unique"}
                          ]
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void omitsEmptyFieldErrorsForApiExceptionWithoutFieldErrors() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {
                          "timestamp": "2026-10-18T09:15:30Z",
                          "status": 404,
                          "code": "MOVIE_NOT_FOUND",
                          "message": "Movie not found"
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void mapsValidationFailureToSortedFieldErrors() throws Exception {
        mockMvc.perform(post("/test/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "x",
                                  "name": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {
                          "timestamp": "2026-10-18T09:15:30Z",
                          "status": 400,
                          "code": "VALIDATION_ERROR",
                          "message": "Request validation failed",
                          "fieldErrors": [
                            {"field": "name", "message": "name is required"},
                            {"field": "title", "message": "title is too short"}
                          ]
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void mapsMalformedJsonToMalformedRequest() throws Exception {
        mockMvc.perform(post("/test/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(MALFORMED_REQUEST_BODY, JsonCompareMode.STRICT));
    }

    @Test
    void mapsMissingBodyToMalformedRequest() throws Exception {
        mockMvc.perform(post("/test/validated")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(MALFORMED_REQUEST_BODY, JsonCompareMode.STRICT));
    }

    record TestRequest(
            @NotBlank(message = "name is required") String name,
            @Size(min = 3, message = "title is too short") String title
    ) {
    }

    @RestController
    static class TestController {

        @PostMapping("/test/validated")
        void validated(@Valid @RequestBody TestRequest request) {
        }

        @PostMapping("/test/conflict")
        void conflict() {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MOVIE_SLUG_CONFLICT",
                    "Movie slug already exists",
                    List.of(new ApiFieldError("slug", "must be unique"))
            );
        }

        @GetMapping("/test/not-found")
        void notFound() {
            throw new ApiException(HttpStatus.NOT_FOUND, "MOVIE_NOT_FOUND", "Movie not found");
        }
    }
}
