package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vodplatform.catalog.web.PersonAdminController;
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
 * {@code /api/v1/admin/people} through the assembled application: the bearer security chain, the
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
class PersonAdminApiIntegrationTests {

    private static final String PEOPLE = "/api/v1/admin/people";
    private static final JsonComparator STRICT_WITH_ANY_TIMESTAMP = JsonAssert.comparator(new CustomComparator(
            JSONCompareMode.STRICT,
            new Customization("timestamp", (actual, expected) -> isInstant(actual))
    ));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private PersonAdminController personAdminController;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousRequestsAreUnauthorized() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                get(PEOPLE),
                json(post(PEOPLE), "{\"name\": \"Lý Hải\"}"),
                json(put(PEOPLE + "/" + UUID.randomUUID()), "{\"name\": \"Lý Hải\"}"))) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(apiError(401, "UNAUTHORIZED", "Authentication required or invalid token", null));
        }
    }

    @Test
    void roleUserIsForbiddenEvenForInvalidInputOrAMissingPerson() throws Exception {
        String user = bearerToken("ROLE_USER");
        for (MockHttpServletRequestBuilder request : List.of(
                get(PEOPLE + "?size=0"),
                json(post(PEOPLE), "{\"name\": \" \"}"),
                json(put(PEOPLE + "/" + UUID.randomUUID()), "{\"name\": \"Lý Hải\"}"),
                json(put(PEOPLE + "/not-a-uuid"), "{}"))) {
            mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, user))
                    .andExpect(status().isForbidden())
                    .andExpect(apiError(403, "FORBIDDEN", "Authenticated user is not allowed to perform this action", null));
        }
    }

    @Test
    void adminCreatesPeopleWithGeneratedSlugsAndRenamesThem() throws Exception {
        String admin = bearerToken("ROLE_ADMIN");
        perform(json(post(PEOPLE), "{\"name\": \"  Trấn   Thành \"}"), admin)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Trấn Thành"))
                .andExpect(jsonPath("$.slug").value("tran-thanh"));
        String secondId = createdId(perform(json(post(PEOPLE), "{\"name\": \"trấn thành\"}"), admin)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("tran-thanh-2")));

        perform(json(put(PEOPLE + "/" + secondId), "{\"name\": \"TRẤN THÀNH\"}"), admin)
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"id": "%s", "name": "TRẤN THÀNH", "slug": "tran-thanh-2"}
                        """.formatted(secondId), JsonCompareMode.STRICT));
        perform(json(put(PEOPLE + "/" + secondId), "{\"name\": \"Lý Hải\"}"), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("ly-hai"));
    }

    @Test
    void searchMatchesTheNormalizedTermInPages() throws Exception {
        String admin = bearerToken("ROLE_ADMIN");
        String first = createdId(perform(json(post(PEOPLE), "{\"name\": \"Nguyễn Văn A\"}"), admin));
        String second = createdId(perform(json(post(PEOPLE), "{\"name\": \"Nguyễn Văn A\"}"), admin));
        String third = createdId(perform(json(post(PEOPLE), "{\"name\": \"Trần Nguyên\"}"), admin));
        perform(json(post(PEOPLE), "{\"name\": \"Lê Văn B\"}"), admin);

        // Ordered by name, then id; the id order of the two equal names is covered with fixed ids
        // by the repository test.
        perform(get(PEOPLE).param("q", " NGUYỄN ").param("page", "0").param("size", "2"), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(containsInAnyOrder(first, second)))
                .andExpect(jsonPath("$.items[*].slug").value(containsInAnyOrder("nguyen-van-a", "nguyen-van-a-2")))
                .andExpect(jsonPath("$.page.page").value(0))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));
        perform(get(PEOPLE).param("q", "nguyen").param("page", "1").param("size", "2"), admin)
                .andExpect(content().json("""
                        {
                          "items": [{"id": "%s", "name": "Trần Nguyên", "slug": "tran-nguyen"}],
                          "page": {"page": 1, "size": 2, "totalElements": 3, "totalPages": 2}
                        }
                        """.formatted(third), JsonCompareMode.STRICT));
        perform(get(PEOPLE), admin)
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.page").value(0));
        perform(get(PEOPLE).param("q", "!!!"), admin)
                .andExpect(jsonPath("$.page.totalElements").value(4));
        perform(get(PEOPLE).param("q", "kim"), admin)
                .andExpect(content().json("""
                        {"items": [], "page": {"page": 0, "size": 20, "totalElements": 0, "totalPages": 0}}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void invalidPagingSearchTermsAndBodiesAreValidationErrors() throws Exception {
        String admin = bearerToken("ROLE_ADMIN");

        perform(get(PEOPLE).param("page", "-1"), admin)
                .andExpect(status().isBadRequest())
                .andExpect(validationError("page", "must be between 0 and 10000"));
        perform(get(PEOPLE).param("page", "10001"), admin)
                .andExpect(validationError("page", "must be between 0 and 10000"));
        perform(get(PEOPLE).param("size", "0"), admin)
                .andExpect(validationError("size", "must be between 1 and 100"));
        perform(get(PEOPLE).param("size", "101"), admin)
                .andExpect(validationError("size", "must be between 1 and 100"));
        perform(get(PEOPLE).param("size", "ten"), admin)
                .andExpect(validationError("size", "must be an integer"));
        perform(get(PEOPLE).param("q", "a".repeat(101)), admin)
                .andExpect(validationError("q", "size must be between 0 and 100"));
        perform(get(PEOPLE).param("page", "10000").param("size", "100"), admin)
                .andExpect(status().isOk());
        perform(json(post(PEOPLE), "{\"name\": \"Lý\\u0000Hải\"}"), admin)
                .andExpect(validationError("name", "must not contain control characters"));
        perform(json(put(PEOPLE + "/" + UUID.randomUUID()), "{\"name\": null}"), admin)
                .andExpect(validationError("name", "must not be null"));
        perform(json(put(PEOPLE + "/not-a-uuid"), "{\"name\": \"Lý Hải\"}"), admin)
                .andExpect(validationError("personId", "must be a valid UUID"));
    }

    @Test
    void renamingAMissingPersonIsNotFound() throws Exception {
        perform(json(put(PEOPLE + "/" + UUID.randomUUID()), "{\"name\": \"Lý Hải\"}"), bearerToken("ROLE_ADMIN"))
                .andExpect(status().isNotFound())
                .andExpect(apiError(404, "PERSON_NOT_FOUND", "Person not found", null));
    }

    @Test
    void peopleCannotBeDeleted() throws Exception {
        perform(delete(PEOPLE + "/" + UUID.randomUUID()), bearerToken("ROLE_ADMIN"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void methodSecurityRequiresTheAdminRoleWithoutTheUrlRule() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user", null, "ROLE_USER"));
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> personAdminController.search(null, 0, 20));

        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin", null, "ROLE_ADMIN"));
        assertThat(personAdminController.search(null, 0, 20).items()).isEmpty();
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
                .claim("email", "people-admin-test@example.com")
                .claim("roles", List.of(role))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims
        )).getTokenValue();
        return "Bearer " + token;
    }
}
