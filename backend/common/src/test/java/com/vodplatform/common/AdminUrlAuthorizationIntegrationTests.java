package com.vodplatform.common;

import static com.vodplatform.common.contract.ContractValidation.offContract;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.skyscreamer.jsonassert.Customization;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.comparator.CustomComparator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.json.JsonAssert;
import org.springframework.test.json.JsonComparator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verifies the URL-level admin rules of the bearer security chain with test-only probe
 * controllers on the catalog paths: authorization runs before argument conversion and
 * validation, and read and user sub-resource routes stay open to any authenticated role.
 */
@SpringBootTest(
        properties = {
                "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
                "auth.tokens.access-token-ttl=15m",
                "auth.tokens.refresh-token-ttl=7d"
        }
)
@AutoConfigureMockMvc
@Import(AdminUrlAuthorizationIntegrationTests.CatalogProbeController.class)
class AdminUrlAuthorizationIntegrationTests {

    /**
     * Probe mappings require this header, so they never clash with the real catalog controllers
     * mapped to the same paths later. Security rules ignore it.
     */
    private static final String PROBE_HEADER = "X-Test-Probe";
    private static final String PROBE_HEADER_VALUE = "admin-url-authorization";
    private static final String PROBE_HEADER_CONDITION = PROBE_HEADER + "=" + PROBE_HEADER_VALUE;
    /**
     * The probe controllers answer {"status": "allowed"} on catalog paths and add paths the
     * contract does not declare, so their responses are not contract responses.
     */
    private static final RequestPostProcessor PROBE = offContract(
            "test-only probe controllers (#147) for the URL authorization rules, not API operations");

    private static final String MOVIE_ID = "6f1d2a4e-8c3b-4a5f-9e7d-1b2c3d4e5f60";
    private static final String VALID_BODY = """
            {"title": "Spirited Away"}
            """;
    private static final String INVALID_BODY = """
            {"title": " "}
            """;
    private static final String TITLE_REQUIRED = """
            {"field": "title", "message": "title is required"}
            """;
    private static final String MOVIE_ID_NOT_UUID = """
            {"field": "movieId", "message": "must be a valid UUID"}
            """;
    private static final JsonComparator STRICT_WITH_ANY_TIMESTAMP = JsonAssert.comparator(new CustomComparator(
            JSONCompareMode.STRICT,
            new Customization("timestamp", (actual, expected) -> isInstant(actual))
    ));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    static Stream<ProbeRequest> adminOperationsWithInvalidInput() {
        return Stream.of(
                new ProbeRequest(HttpMethod.POST, "/api/v1/admin/probe", INVALID_BODY),
                new ProbeRequest(HttpMethod.POST, "/api/v1/movies", INVALID_BODY),
                new ProbeRequest(HttpMethod.PUT, "/api/v1/movies/" + MOVIE_ID, INVALID_BODY),
                new ProbeRequest(HttpMethod.PUT, "/api/v1/movies/not-a-uuid", INVALID_BODY),
                new ProbeRequest(HttpMethod.DELETE, "/api/v1/movies/not-a-uuid", null)
        );
    }

    static Stream<Arguments> adminOperationsWithInvalidInputAndTheirFieldErrors() {
        return Stream.of(
                Arguments.of(new ProbeRequest(HttpMethod.POST, "/api/v1/admin/probe", INVALID_BODY), TITLE_REQUIRED),
                Arguments.of(new ProbeRequest(HttpMethod.POST, "/api/v1/movies", INVALID_BODY), TITLE_REQUIRED),
                Arguments.of(new ProbeRequest(HttpMethod.PUT, "/api/v1/movies/" + MOVIE_ID, INVALID_BODY), TITLE_REQUIRED),
                Arguments.of(new ProbeRequest(HttpMethod.PUT, "/api/v1/movies/not-a-uuid", VALID_BODY), MOVIE_ID_NOT_UUID),
                Arguments.of(new ProbeRequest(HttpMethod.DELETE, "/api/v1/movies/not-a-uuid", null), MOVIE_ID_NOT_UUID)
        );
    }

    static Stream<ProbeRequest> adminOperationsWithValidInput() {
        return Stream.of(
                new ProbeRequest(HttpMethod.POST, "/api/v1/admin/probe", VALID_BODY),
                new ProbeRequest(HttpMethod.POST, "/api/v1/movies", VALID_BODY),
                new ProbeRequest(HttpMethod.PUT, "/api/v1/movies/" + MOVIE_ID, VALID_BODY),
                new ProbeRequest(HttpMethod.DELETE, "/api/v1/movies/" + MOVIE_ID, null)
        );
    }

    static Stream<ProbeRequest> adminPathsWithoutHandler() {
        return Stream.of(
                new ProbeRequest(HttpMethod.GET, "/api/v1/admin", null),
                new ProbeRequest(HttpMethod.GET, "/api/v1/admin/movies", null),
                new ProbeRequest(HttpMethod.DELETE, "/api/v1/admin/genres/" + MOVIE_ID, null)
        );
    }

    static Stream<ProbeRequest> authenticatedReadAndUserSubResourceOperations() {
        return Stream.of(
                new ProbeRequest(HttpMethod.GET, "/api/v1/movies", null),
                new ProbeRequest(HttpMethod.GET, "/api/v1/movies/" + MOVIE_ID, null),
                new ProbeRequest(HttpMethod.PUT, "/api/v1/movies/a/b", VALID_BODY),
                new ProbeRequest(HttpMethod.DELETE, "/api/v1/movies/a/b", null)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource({"adminOperationsWithInvalidInput", "authenticatedReadAndUserSubResourceOperations"})
    void anonymousRequestReceivesUnauthorized(ProbeRequest probe) throws Exception {
        perform(probe, null)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(apiError(401, "UNAUTHORIZED", "Authentication required or invalid token", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminOperationsWithInvalidInput")
    void roleUserReceivesForbiddenForAdminOperationBeforeValidation(ProbeRequest probe) throws Exception {
        perform(probe, bearerToken("ROLE_USER"))
                .andExpect(status().isForbidden())
                .andExpect(forbidden());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminPathsWithoutHandler")
    void roleUserReceivesForbiddenForAdminPathEvenWithoutHandler(ProbeRequest probe) throws Exception {
        perform(probe, bearerToken("ROLE_USER"))
                .andExpect(status().isForbidden())
                .andExpect(forbidden());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminOperationsWithInvalidInputAndTheirFieldErrors")
    void roleAdminWithInvalidInputReceivesValidationError(ProbeRequest probe, String fieldError) throws Exception {
        perform(probe, bearerToken("ROLE_ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(apiError(400, "VALIDATION_ERROR", "Request validation failed", fieldError));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminOperationsWithValidInput")
    void roleAdminWithValidInputReachesAdminOperation(ProbeRequest probe) throws Exception {
        perform(probe, bearerToken("ROLE_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"status": "allowed"}
                        """, STRICT_WITH_ANY_TIMESTAMP));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("authenticatedReadAndUserSubResourceOperations")
    void roleUserCanUseReadAndUserSubResourceOperations(ProbeRequest probe) throws Exception {
        perform(probe, bearerToken("ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"status": "allowed"}
                        """, STRICT_WITH_ANY_TIMESTAMP));
    }

    @Test
    void roleUserReadWithNonUuidMovieIdReceivesValidationError() throws Exception {
        perform(new ProbeRequest(HttpMethod.GET, "/api/v1/movies/not-a-uuid", null), bearerToken("ROLE_USER"))
                .andExpect(status().isBadRequest())
                .andExpect(apiError(400, "VALIDATION_ERROR", "Request validation failed", MOVIE_ID_NOT_UUID));
    }

    @Test
    void roleUserReadWithInvalidQueryParameterReceivesValidationError() throws Exception {
        perform(new ProbeRequest(HttpMethod.GET, "/api/v1/movies?size=101", null), bearerToken("ROLE_USER"))
                .andExpect(status().isBadRequest())
                .andExpect(apiError(400, "VALIDATION_ERROR", "Request validation failed", """
                        {"field": "size", "message": "size must be between 1 and 100"}
                        """));

        perform(new ProbeRequest(HttpMethod.GET, "/api/v1/movies?size=ten", null), bearerToken("ROLE_USER"))
                .andExpect(status().isBadRequest())
                .andExpect(apiError(400, "VALIDATION_ERROR", "Request validation failed", """
                        {"field": "size", "message": "must be an integer"}
                        """));
    }

    private ResultActions perform(ProbeRequest probe, String authorization) throws Exception {
        MockHttpServletRequestBuilder request = request(probe.method(), probe.path())
                .header(PROBE_HEADER, PROBE_HEADER_VALUE)
                .with(PROBE);
        if (probe.body() != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(probe.body());
        }
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mockMvc.perform(request);
    }

    private static ResultMatcher forbidden() {
        return apiError(403, "FORBIDDEN", "Authenticated user is not allowed to perform this action", null);
    }

    private static ResultMatcher apiError(int status, String code, String message, String fieldError) {
        String fieldErrors = fieldError == null ? "" : ", \"fieldErrors\": [" + fieldError + "]";
        return content().json("""
                {
                  "timestamp": "any instant",
                  "status": %d,
                  "code": "%s",
                  "message": "%s"%s
                }
                """.formatted(status, code, message, fieldErrors), STRICT_WITH_ANY_TIMESTAMP);
    }

    private static boolean isInstant(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        try {
            Instant.parse(text);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private String bearerToken(String role) {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("userId", userId.toString())
                .claim("email", "admin-url-test@example.com")
                .claim("roles", List.of(role))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims
        )).getTokenValue();
        return "Bearer " + token;
    }

    record ProbeRequest(HttpMethod method, String path, String body) {

        @Override
        public String toString() {
            return method + " " + path + (body == null ? "" : " " + body.strip());
        }
    }

    record ProbeMovieRequest(@NotBlank(message = "title is required") String title) {
    }

    record ProbeResponse(String status) {
    }

    @RestController
    static class CatalogProbeController {

        private static final ProbeResponse ALLOWED = new ProbeResponse("allowed");

        @PostMapping(path = "/api/v1/admin/probe", headers = PROBE_HEADER_CONDITION)
        ProbeResponse adminProbe(@Valid @RequestBody ProbeMovieRequest request) {
            return ALLOWED;
        }

        @GetMapping(path = "/api/v1/movies", headers = PROBE_HEADER_CONDITION)
        ProbeResponse listMovies(
                @RequestParam(name = "size", defaultValue = "20")
                @Min(value = 1, message = "size must be between 1 and 100")
                @Max(value = 100, message = "size must be between 1 and 100") int size
        ) {
            return ALLOWED;
        }

        @PostMapping(path = "/api/v1/movies", headers = PROBE_HEADER_CONDITION)
        ProbeResponse createMovie(@Valid @RequestBody ProbeMovieRequest request) {
            return ALLOWED;
        }

        @GetMapping(path = "/api/v1/movies/{movieId}", headers = PROBE_HEADER_CONDITION)
        ProbeResponse getMovie(@PathVariable UUID movieId) {
            return ALLOWED;
        }

        @PutMapping(path = "/api/v1/movies/{movieId}", headers = PROBE_HEADER_CONDITION)
        ProbeResponse updateMovie(@PathVariable UUID movieId, @Valid @RequestBody ProbeMovieRequest request) {
            return ALLOWED;
        }

        @DeleteMapping(path = "/api/v1/movies/{movieId}", headers = PROBE_HEADER_CONDITION)
        ProbeResponse archiveMovie(@PathVariable UUID movieId) {
            return ALLOWED;
        }

        @PutMapping(path = "/api/v1/movies/{movieId}/{subResource}", headers = PROBE_HEADER_CONDITION)
        ProbeResponse updateUserSubResource(
                @PathVariable String movieId,
                @PathVariable String subResource,
                @RequestBody ProbeMovieRequest request
        ) {
            return ALLOWED;
        }

        @DeleteMapping(path = "/api/v1/movies/{movieId}/{subResource}", headers = PROBE_HEADER_CONDITION)
        ProbeResponse deleteUserSubResource(@PathVariable String movieId, @PathVariable String subResource) {
            return ALLOWED;
        }
    }
}
