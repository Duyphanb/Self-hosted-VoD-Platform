package com.vodplatform.common.contract;

import static com.vodplatform.common.contract.ContractValidation.matchesContract;
import static com.vodplatform.common.contract.ContractValidation.offContract;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.networknt.schema.SchemaException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The response validator against the repository contract, plus a few synthetic contracts for
 * rules the frozen contract does not use yet. Each failing case changes one thing in a body that
 * otherwise conforms.
 */
class ApiContractTests {

    private static final String JSON = "application/json";
    private static final String USER_ID = "6f1d2a4e-8c3b-4a5f-9e7d-1b2c3d4e5f60";
    private static final String MOVIE_ID = "0b6c1f7e-2a3d-4e5f-8a9b-0c1d2e3f4a5b";
    private static final String USER = """
            {"id": "%s", "email": "viewer@example.com", "displayName": "Viewer", "roles": ["ROLE_USER"]}
            """.formatted(USER_ID);
    private static final String AUTH_RESPONSE = """
            {"user": %s, "accessToken": "access", "refreshToken": "refresh", "expiresInSeconds": 900}
            """.formatted(USER);
    private static final String MOVIE_SUMMARY = """
            {"id": "%s", "title": "Spirited Away", "slug": "spirited-away", "description": "A girl.",
             "releaseYear": null, "posterUrl": null, "genres": ["Animation"], "averageRating": null,
             "status": "PUBLISHED", "videoStatus": null}
            """.formatted(MOVIE_ID);
    private static final String MOVIE_DETAIL = """
            {"id": "%s", "title": "Spirited Away", "slug": "spirited-away", "description": "A girl.",
             "releaseYear": 2001, "posterUrl": null, "genres": ["Animation"], "averageRating": 4.5,
             "status": "DRAFT", "videoStatus": "READY", "maturityRating": null,
             "people": [{"personId": "%s", "name": "Hayao Miyazaki", "role": "DIRECTOR"}],
             "userRating": null, "inWatchlist": false}
            """.formatted(MOVIE_ID, USER_ID);
    private static final String API_ERROR = """
            {"timestamp": "2026-10-26T10:15:30.123456Z", "status": 400, "code": "VALIDATION_ERROR",
             "message": "Request validation failed", "fieldErrors": [{"field": "email", "message": "must not be blank"}]}
            """;

    private final ApiContract contract = ApiContract.get();

    @Test
    void everyJsonResponseSchemaOfTheContractCompiles() {
        assertThat(contract.compileAllResponseSchemas()).isGreaterThan(50);
    }

    @Test
    void conformingResponsesHaveNoViolations() {
        assertThat(violations("POST", "/api/v1/auth/login", 200, AUTH_RESPONSE)).isEmpty();
        assertThat(violations("POST", "/api/v1/auth/login", 400, API_ERROR)).isEmpty();
        assertThat(contract.violations("POST", "/api/v1/auth/login", 200, "application/json;charset=UTF-8",
                bytes(AUTH_RESPONSE))).isEmpty();
        assertThat(contract.violations("POST", "/api/v1/auth/logout", 204, null, new byte[0])).isEmpty();
    }

    @Test
    void schemaViolationsAreReportedWithTheirLocation() {
        assertThat(violations("POST", "/api/v1/auth/login", 200, AUTH_RESPONSE.replace(", \"expiresInSeconds\": 900", "")))
                .singleElement().asString().contains("$", "expiresInSeconds");
        assertThat(violations("POST", "/api/v1/auth/login", 200, AUTH_RESPONSE.replace("900", "\"900\"")))
                .singleElement().asString().startsWith("$.expiresInSeconds");
        assertThat(violations("POST", "/api/v1/auth/login", 200, AUTH_RESPONSE.replace("ROLE_USER", "ROLE_ROOT")))
                .singleElement().asString().startsWith("$.user.roles[0]");
        assertThat(violations("POST", "/api/v1/auth/login", 200, AUTH_RESPONSE.replace("\"Viewer\"", "null")))
                .singleElement().asString().startsWith("$.user.displayName");
        assertThat(violations("POST", "/api/v1/auth/login", 200, AUTH_RESPONSE.replace(USER_ID, "not-a-uuid")))
                .singleElement().asString().startsWith("$.user.id");
        assertThat(violations("POST", "/api/v1/auth/login", 200, AUTH_RESPONSE.replace("viewer@example.com", "viewer")))
                .singleElement().asString().startsWith("$.user.email");
        assertThat(violations("POST", "/api/v1/auth/login", 400, API_ERROR.replace("2026-10-26T10:15:30.123456Z", "today")))
                .singleElement().asString().startsWith("$.timestamp");
    }

    @Test
    void undeclaredPropertiesAreReportedAtAnyDepth() {
        assertThat(violations("POST", "/api/v1/auth/login", 200, AUTH_RESPONSE.replace("\"displayName\"",
                "\"passwordHash\": \"hash\", \"displayName\"")))
                .containsExactly("$.user.passwordHash: property is not declared by the response schema");
        assertThat(violations("POST", "/api/v1/auth/login", 400, API_ERROR.replace("\"code\"", "\"trace\": \"x\", \"code\"")))
                .containsExactly("$.trace: property is not declared by the response schema");
    }

    @Test
    void nullableFieldsAllOfCompositionAndNestedItemsAreValidated() {
        String moviePath = "/api/v1/movies/" + MOVIE_ID;
        assertThat(violations("GET", moviePath, 200, MOVIE_DETAIL)).isEmpty();
        assertThat(violations("GET", moviePath, 200, MOVIE_DETAIL.replace("\"maturityRating\": null", "\"maturityRating\": \"T16\"")))
                .isEmpty();
        assertThat(violations("GET", moviePath, 200, MOVIE_DETAIL.replace("\"maturityRating\": null", "\"maturityRating\": \"C\"")))
                .singleElement().asString().startsWith("$.maturityRating");
        assertThat(violations("GET", moviePath, 200, MOVIE_DETAIL.replace("\"videoStatus\": \"READY\"", "\"videoStatus\": \"DONE\"")))
                .singleElement().asString().startsWith("$.videoStatus");
        assertThat(violations("GET", moviePath, 200, MOVIE_DETAIL.replace("\"role\": \"DIRECTOR\"", "\"role\": \"WRITER\"")))
                .singleElement().asString().startsWith("$.people[0].role");
        assertThat(violations("GET", moviePath, 200, MOVIE_DETAIL.replace("\"inWatchlist\": false", "\"inWatchlist\": false, \"budget\": 1")))
                .containsExactly("$.budget: property is not declared by the response schema");
        assertThat(violations("GET", moviePath, 200, MOVIE_DETAIL.replace("\"title\": \"Spirited Away\", ", "")))
                .singleElement().asString().contains("title");

        String page = """
                {"items": [%s], "page": {"page": 0, "size": 20, "totalElements": 1, "totalPages": 1}}
                """.formatted(MOVIE_SUMMARY);
        assertThat(violations("GET", "/api/v1/movies", 200, page)).isEmpty();
        assertThat(violations("GET", "/api/v1/movies", 200, page.replace("\"videoStatus\": null", "\"videoStatus\": null, \"extra\": true")))
                .containsExactly("$.items[0].extra: property is not declared by the response schema");
        assertThat(violations("GET", "/api/v1/movies", 200, page.replace("\"totalPages\": 1", "\"totalPages\": 1.5")))
                .singleElement().asString().startsWith("$.page.totalPages");
    }

    @Test
    void undeclaredPathsOperationsAndStatusesAreReported() {
        assertThat(violations("GET", "/api/v1/unknown", 200, "{}"))
                .containsExactly("path /api/v1/unknown is not declared");
        assertThat(violations("DELETE", "/api/v1/admin/genres/" + MOVIE_ID, 204, ""))
                .containsExactly("operation DELETE /api/v1/admin/genres/{genreId} is not declared");
        assertThat(violations("POST", "/api/v1/auth/login", 404, API_ERROR))
                .containsExactly("status 404 is not declared for POST /api/v1/auth/login");
    }

    @Test
    void bodiesMustMatchTheDeclaredContent() {
        assertThat(violations("POST", "/api/v1/auth/logout", 204, "{}"))
                .containsExactly("status 204 declares no body, but the response has one");
        assertThat(violations("POST", "/api/v1/auth/login", 200, ""))
                .containsExactly("status 200 declares a body, but the response is empty");
        assertThat(contract.violations("POST", "/api/v1/auth/login", 200, "text/plain", bytes(AUTH_RESPONSE)))
                .containsExactly("content type text/plain is not declared for status 200 (declared: application/json)");
        assertThat(violations("POST", "/api/v1/auth/login", 200, "{\"user\": "))
                .containsExactly("the response body is not valid JSON");
    }

    @Test
    void literalSegmentsRangesAndDefaultResponsesFollowOpenApiPrecedence() {
        ApiContract synthetic = ApiContract.parse("""
                openapi: 3.0.3
                info: {title: Synthetic, version: "1"}
                paths:
                  /items/{itemId}:
                    get:
                      responses:
                        "200": {description: Item, content: {application/json: {schema: {$ref: "#/components/schemas/Item"}}}}
                        "4XX": {description: Client error}
                  /items/latest:
                    get:
                      responses:
                        "200": {description: Latest, content: {application/json: {schema: {type: string}}}}
                        default: {description: Anything else, content: {application/json: {schema: {$ref: "#/components/schemas/Open"}}}}
                components:
                  schemas:
                    Item:
                      type: object
                      required: [id]
                      properties:
                        id: {type: integer}
                    Open:
                      type: object
                      additionalProperties: true
                      properties:
                        code: {type: string}
                """);

        assertThat(synthetic.violations("GET", "/items/latest", 200, JSON, bytes("\"latest\""))).isEmpty();
        assertThat(synthetic.violations("GET", "/items/7", 200, JSON, bytes("{\"id\": 7}"))).isEmpty();
        assertThat(synthetic.violations("GET", "/items/7", 404, null, new byte[0])).isEmpty();
        assertThat(synthetic.violations("GET", "/items/7", 500, null, new byte[0]))
                .containsExactly("status 500 is not declared for GET /items/{itemId}");
        assertThat(synthetic.violations("GET", "/items/latest", 503, JSON, bytes("{\"code\": \"DOWN\", \"retry\": 5}")))
                .isEmpty();
        assertThat(synthetic.violations("GET", "/items/latest", 503, JSON, bytes("{\"code\": 1}")))
                .singleElement().asString().startsWith("$.code");
    }

    @Test
    void additionalPropertiesFalseIsEnforcedByTheSchema() {
        ApiContract synthetic = ApiContract.parse("""
                openapi: 3.0.3
                info: {title: Synthetic, version: "1"}
                paths:
                  /closed:
                    get:
                      responses:
                        "200":
                          description: Closed
                          content:
                            application/json:
                              schema: {type: object, additionalProperties: false, properties: {id: {type: integer}}}
                """);

        assertThat(synthetic.violations("GET", "/closed", 200, JSON, bytes("{\"id\": 1}"))).isEmpty();
        assertThat(synthetic.violations("GET", "/closed", 200, JSON, bytes("{\"id\": 1, \"name\": \"x\"}")))
                .singleElement().asString().contains("name");
    }

    @Test
    void unknownSchemaKeywordsAreRejectedInsteadOfIgnored() {
        ApiContract synthetic = ApiContract.parse("""
                openapi: 3.0.3
                info: {title: Synthetic, version: "1"}
                paths:
                  /typo:
                    get:
                      responses:
                        "200":
                          description: Typo
                          content:
                            application/json:
                              schema: {type: string, nulable: true}
                """);

        assertThatExceptionOfType(SchemaException.class)
                .isThrownBy(() -> synthetic.violations("GET", "/typo", 200, JSON, bytes("\"x\"")))
                .withMessageContaining("'nulable'");
    }

    @Test
    void exclusiveBoundsAndSpecificationExtensionsAreOpenApiKeywords() {
        ApiContract synthetic = ApiContract.parse("""
                openapi: 3.0.3
                info: {title: Synthetic, version: "1"}
                paths:
                  /count:
                    get:
                      responses:
                        "200":
                          description: Count
                          content:
                            application/json:
                              schema: {type: integer, minimum: 1, exclusiveMinimum: true, x-note: positive}
                """);

        assertThat(synthetic.violations("GET", "/count", 200, JSON, bytes("2"))).isEmpty();
        assertThat(synthetic.violations("GET", "/count", 200, JSON, bytes("1"))).singleElement().asString().startsWith("$");
    }

    @Test
    void matcherFailsOnViolationsUnlessTheRequestIsMarkedOffContract() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new HealthStub()).build();

        assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(() -> mockMvc.perform(get("/api/v1/health")).andExpect(matchesContract()))
                .withMessageContaining("GET /api/v1/health (status 200)")
                .withMessageContaining("$.uptime: property is not declared by the response schema");
        mockMvc.perform(get("/api/v1/health").with(offContract("stub that adds a field on purpose")))
                .andExpect(matchesContract());
        assertThatIllegalArgumentException().isThrownBy(() -> offContract(" "));
    }

    private List<String> violations(String method, String path, int status, String body) {
        return contract.violations(method, path, status, body.isEmpty() ? null : JSON, bytes(body));
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    @RestController
    static class HealthStub {

        @GetMapping("/api/v1/health")
        Map<String, Object> health() {
            return Map.of("status", "UP", "uptime", 5);
        }
    }
}
