package com.vodplatform.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.ServletException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.annotation.Validated;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Covers the request parameter and method validation mappings of {@link ApiExceptionHandler}:
 * type mismatches, Spring MVC method validation and Bean Validation constraint violations.
 */
class ApiExceptionHandlerParameterValidationTest {

    private static final Instant NOW = Instant.parse("2026-10-18T09:15:30Z");

    private LocalValidatorFactoryBean validator;
    private ApiExceptionHandler handler;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        handler = new ApiExceptionHandler(new ApiErrorFactory(Clock.fixed(NOW, ZoneOffset.UTC)));

        ProxyFactory proxyFactory = new ProxyFactory(new ValidatedService());
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new MethodValidationInterceptor((jakarta.validation.Validator) validator));
        ValidatedService validatedService = (ValidatedService) proxyFactory.getProxy();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController(validatedService))
                .setControllerAdvice(handler)
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void closeValidatorFactory() {
        validator.close();
    }

    @ParameterizedTest
    @CsvSource({
            "/test/movies/not-a-uuid, movieId, must be a valid UUID",
            "/test/movies?size=twenty, size, must be an integer",
            "/test/movies?sort=oldest, sort, is not an allowed value",
            "/test/movies?ratio=high, ratio, must be a number",
            "/test/movies?featured=maybe, featured, must be true or false",
            "/test/movies?since=yesterday, since, has an invalid value"
    })
    void mapsTypeMismatchToValidationErrorWithoutEchoingTheValue(String uri, String field, String message)
            throws Exception {
        mockMvc.perform(get(uri))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(validationError("""
                        {"field": "%s", "message": "%s"}
                        """.formatted(field, message)), JsonCompareMode.STRICT));
    }

    @Test
    void mapsMethodValidationToFieldErrorsNamedAfterTheRequestBinding() throws Exception {
        mockMvc.perform(get("/test/movies")
                        .param("size", "0")
                        .param("q", "far-too-long")
                        .param("tag", "drama", " "))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(validationError("""
                        {"field": "q", "message": "q is too long"},
                        {"field": "size", "message": "size must be at least 1"},
                        {"field": "tag[1]", "message": "tag must not be blank"}
                        """), JsonCompareMode.STRICT));
    }

    @Test
    void mapsMethodValidationOfRequestBodyToPropertyFieldErrors() throws Exception {
        mockMvc.perform(post("/test/movies/method-validated")
                        .param("limit", "500")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "x",
                                  "name": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(validationError("""
                        {"field": "limit", "message": "limit must be at most 100"},
                        {"field": "name", "message": "name is required"},
                        {"field": "title", "message": "title is too short"}
                        """), JsonCompareMode.STRICT));
    }

    @Test
    void leavesReturnValueMethodValidationToDefaultHandling() throws Exception {
        mockMvc.perform(get("/test/movies/invalid-return-value"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(""));
    }

    @Test
    void mapsConstraintViolationsToParameterAndPropertyPaths() throws Exception {
        mockMvc.perform(post("/test/service-validation"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(validationError("""
                        {"field": "count", "message": "count must be positive"},
                        {"field": "request.name", "message": "name is required"},
                        {"field": "request.title", "message": "title is too short"},
                        {"field": "tags[1]", "message": "tag must not be blank"}
                        """), JsonCompareMode.STRICT));
    }

    @Test
    void leavesReturnValueConstraintViolationsToDefaultHandling() {
        assertThatExceptionOfType(ServletException.class)
                .isThrownBy(() -> mockMvc.perform(get("/test/service-validation/invalid-return-value")))
                .withCauseInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void mapsConstraintViolationExceptionWithoutViolationsToValidationErrorWithoutFieldErrors() {
        ResponseEntity<ApiError> response = handler.handleConstraintViolation(
                new ConstraintViolationException("internal detail", null)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(new ApiError(
                NOW,
                400,
                "VALIDATION_ERROR",
                "Request validation failed",
                List.of()
        ));
    }

    @Test
    void replacesBindingFailureMessagesThatWouldQuoteTheRejectedValue() throws Exception {
        mockMvc.perform(get("/test/search").param("year", "nineteen-ninety"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(validationError("""
                        {"field": "year", "message": "has an invalid value"}
                        """), JsonCompareMode.STRICT));
    }

    private static String validationError(String fieldErrors) {
        return """
                {
                  "timestamp": "2026-10-18T09:15:30Z",
                  "status": 400,
                  "code": "VALIDATION_ERROR",
                  "message": "Request validation failed",
                  "fieldErrors": [%s]
                }
                """.formatted(fieldErrors);
    }

    enum MovieSort {
        LATEST,
        TITLE
    }

    record TestRequest(
            @NotBlank(message = "name is required") String name,
            @Size(min = 3, message = "title is too short") String title
    ) {
    }

    record SearchQuery(Integer year) {
    }

    @Validated
    static class ValidatedService {

        public void register(
                @Min(value = 1, message = "count must be positive") int count,
                @Valid TestRequest request,
                List<@NotBlank(message = "tag must not be blank") String> tags
        ) {
        }

        @NotNull
        public String missingName() {
            return null;
        }
    }

    @RestController
    static class TestController {

        private final ValidatedService validatedService;

        TestController(ValidatedService validatedService) {
            this.validatedService = validatedService;
        }

        @GetMapping("/test/movies/{movieId}")
        void movie(@PathVariable UUID movieId) {
        }

        @GetMapping("/test/movies")
        void movies(
                @RequestParam(name = "size", defaultValue = "20")
                @Min(value = 1, message = "size must be at least 1") int pageSize,
                @RequestParam(value = "q", required = false) @Size(max = 5, message = "q is too long") String query,
                @RequestParam(name = "tag", required = false)
                List<@NotBlank(message = "tag must not be blank") String> tags,
                @RequestParam(required = false) MovieSort sort,
                @RequestParam(required = false) Double ratio,
                @RequestParam(required = false) Boolean featured,
                @RequestParam(required = false) Instant since
        ) {
        }

        @PostMapping("/test/movies/method-validated")
        void methodValidated(
                @RequestParam @Max(value = 100, message = "limit must be at most 100") int limit,
                @Valid @RequestBody TestRequest request
        ) {
        }

        @GetMapping("/test/movies/invalid-return-value")
        @NotNull
        String invalidReturnValue() {
            return null;
        }

        @PostMapping("/test/service-validation")
        void serviceValidation() {
            validatedService.register(0, new TestRequest("", "x"), List.of("drama", " "));
        }

        @GetMapping("/test/service-validation/invalid-return-value")
        String serviceReturnValue() {
            return validatedService.missingName();
        }

        @GetMapping("/test/search")
        void search(@Valid @ModelAttribute SearchQuery query) {
        }
    }
}
