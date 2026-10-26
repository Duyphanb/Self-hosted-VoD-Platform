package com.vodplatform.common;

import static com.vodplatform.common.contract.ContractValidation.offContract;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vodplatform.catalog.dto.GenreResponse;
import com.vodplatform.catalog.web.GenreAdminController;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.Customization;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.comparator.CustomComparator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.json.JsonAssert;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.json.JsonComparator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code /api/v1/admin/genres} through the assembled application: the bearer security chain, the
 * controller, the service and the H2 database (PostgreSQL mode). Each test rolls back.
 */
@SpringBootTest(
        properties = {
                "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
                "auth.tokens.access-token-ttl=15m",
                "auth.tokens.refresh-token-ttl=7d"
        }
)
@AutoConfigureMockMvc
@Transactional
class GenreAdminApiIntegrationTests {

    private static final String GENRES = "/api/v1/admin/genres";
    private static final JsonComparator STRICT_WITH_ANY_TIMESTAMP = JsonAssert.comparator(new CustomComparator(
            JSONCompareMode.STRICT,
            new Customization("timestamp", (actual, expected) -> isInstant(actual))
    ));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private GenreAdminController genreAdminController;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousRequestsAreUnauthorized() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                get(GENRES),
                json(post(GENRES), "{\"name\": \"Hài\"}"),
                json(put(GENRES + "/" + UUID.randomUUID()), "{\"name\": \"Hài\"}"))) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(apiError(401, "UNAUTHORIZED", "Authentication required or invalid token", null));
        }
    }

    @Test
    void roleUserIsForbiddenEvenForAnInvalidBodyOrAMissingGenre() throws Exception {
        String user = bearerToken("ROLE_USER");
        for (MockHttpServletRequestBuilder request : List.of(
                get(GENRES),
                json(post(GENRES), "{\"name\": \" \"}"),
                json(put(GENRES + "/" + UUID.randomUUID()), "{\"name\": \"Hài\"}"),
                json(put(GENRES + "/not-a-uuid"), "{}"))) {
            mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, user))
                    .andExpect(status().isForbidden())
                    .andExpect(apiError(403, "FORBIDDEN", "Authenticated user is not allowed to perform this action", null));
        }
    }

    @Test
    void adminCreatesListsAndRenamesGenres() throws Exception {
        String admin = bearerToken("ROLE_ADMIN");
        String actionId = createdId(perform(json(post(GENRES), "{\"name\": \"  Hành   Động \"}"), admin)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Hành Động"))
                .andExpect(jsonPath("$.slug").value("hanh-dong")));
        String comedyId = createdId(perform(json(post(GENRES), "{\"name\": \"Hài\"}"), admin)
                .andExpect(status().isCreated()));

        perform(get(GENRES), admin)
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [
                          {"id": "%s", "name": "Hài", "slug": "hai"},
                          {"id": "%s", "name": "Hành Động", "slug": "hanh-dong"}
                        ]
                        """.formatted(comedyId, actionId), JsonCompareMode.STRICT));
        perform(json(put(GENRES + "/" + comedyId), "{\"name\": \" Hài   Hước \"}"), admin)
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"id": "%s", "name": "Hài Hước", "slug": "hai-huoc"}
                        """.formatted(comedyId), JsonCompareMode.STRICT));
    }

    @Test
    void aGenreWithTheNameOrSlugOfAnotherGenreIsAConflict() throws Exception {
        String admin = bearerToken("ROLE_ADMIN");
        perform(json(post(GENRES), "{\"name\": \"Hài\"}"), admin).andExpect(status().isCreated());
        String horrorId = createdId(perform(json(post(GENRES), "{\"name\": \"Kinh Dị\"}"), admin));

        perform(json(post(GENRES), "{\"name\": \"HAI\"}"), admin)
                .andExpect(status().isConflict())
                .andExpect(apiError(409, "GENRE_CONFLICT", "Another genre already has this name or slug", null));
        perform(json(put(GENRES + "/" + horrorId), "{\"name\": \"hài\"}"), admin)
                .andExpect(status().isConflict())
                .andExpect(apiError(409, "GENRE_CONFLICT", "Another genre already has this name or slug", null));
    }

    @Test
    void invalidRequestsAreValidationErrors() throws Exception {
        String admin = bearerToken("ROLE_ADMIN");

        perform(json(post(GENRES), "{\"name\": \" \"}"), admin)
                .andExpect(status().isBadRequest())
                .andExpect(validationError("name", "must not be blank"));
        perform(json(post(GENRES), "{}"), admin)
                .andExpect(validationError("name", "must not be null"));
        perform(json(post(GENRES), "{\"name\": \"!!!\"}"), admin)
                .andExpect(validationError("name", "must contain at least one letter or digit"));
        perform(json(post(GENRES), "{\"name\": \"" + "a".repeat(101) + "\"}"), admin)
                .andExpect(validationError("name", "size must be between 1 and 100"));
        perform(json(put(GENRES + "/not-a-uuid"), "{\"name\": \"Hài\"}"), admin)
                .andExpect(validationError("genreId", "must be a valid UUID"));
        perform(json(post(GENRES), "{\"name\": "), admin)
                .andExpect(status().isBadRequest())
                .andExpect(apiError(400, "MALFORMED_REQUEST", "Request body is missing or malformed", null));
    }

    @Test
    void renamingAMissingGenreIsNotFound() throws Exception {
        perform(json(put(GENRES + "/" + UUID.randomUUID()), "{\"name\": \"Hài\"}"), bearerToken("ROLE_ADMIN"))
                .andExpect(status().isNotFound())
                .andExpect(apiError(404, "GENRE_NOT_FOUND", "Genre not found", null));
    }

    @Test
    void genresCannotBeDeleted() throws Exception {
        perform(delete(GENRES + "/" + UUID.randomUUID())
                        .with(offContract("the contract declares no DELETE; the test proves the method is rejected")),
                bearerToken("ROLE_ADMIN"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void methodSecurityRequiresTheAdminRoleWithoutTheUrlRule() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user", null, "ROLE_USER"));
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(genreAdminController::list);

        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin", null, "ROLE_ADMIN"));
        assertThat(genreAdminController.list()).isEqualTo(List.<GenreResponse>of());
    }

    // ---- helpers ----

    private ResultActions perform(MockHttpServletRequestBuilder request, String authorization) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, authorization));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String createdId(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static ResultMatcher validationError(String field, String message) {
        return apiError(400, "VALIDATION_ERROR", "Request validation failed",
                "{\"field\": \"%s\", \"message\": \"%s\"}".formatted(field, message));
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
                .claim("email", "genre-admin-test@example.com")
                .claim("roles", List.of(role))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims
        )).getTokenValue();
        return "Bearer " + token;
    }
}
