package com.vodplatform.common.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.serialization.JsonMapperFactory;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The movie response schemas of the contract (Issue #153): {@code MovieSummary} and
 * {@code MovieDetail} require every field the API always emits, keep their nullable fields
 * nullable, and bound {@code releaseYear}, {@code averageRating} and {@code userRating} to their
 * input domains. Summaries are checked as items of {@code GET /api/v1/movies} and details as
 * {@code GET /api/v1/movies/{movieId}}; each failing case changes one field of a body that
 * otherwise conforms.
 */
class MovieResponseSchemaTests {

    private static final String MOVIE_ID = "0b6c1f7e-2a3d-4e5f-8a9b-0c1d2e3f4a5b";
    private static final String PERSON_ID = "6f1d2a4e-8c3b-4a5f-9e7d-1b2c3d4e5f60";
    private static final String LIST_PATH = "/api/v1/movies";
    private static final String DETAIL_PATH = LIST_PATH + "/" + MOVIE_ID;
    private static final String ITEM = "$.items[0]";
    private static final ObjectMapper MAPPER = JsonMapperFactory.getInstance();

    private static final List<String> SUMMARY_KEYS = List.of(
            "id", "title", "slug", "description", "releaseYear", "posterUrl", "genres", "averageRating", "status",
            "videoStatus");
    private static final List<String> DETAIL_ONLY_KEYS = List.of("maturityRating", "people", "userRating", "inWatchlist");

    private static final String SUMMARY = """
            {"id": "%s", "title": "Spirited Away", "slug": "spirited-away", "description": "A girl.",
             "releaseYear": 2001, "posterUrl": "https://cdn.example.com/poster.jpg", "genres": ["Animation"],
             "averageRating": 4.5, "status": "PUBLISHED", "videoStatus": "READY"}
            """.formatted(MOVIE_ID);
    private static final String DETAIL_ONLY = """
            {"maturityRating": "P", "people": [{"personId": "%s", "name": "Hayao Miyazaki", "role": "DIRECTOR"}],
             "userRating": 4, "inWatchlist": true}
            """.formatted(PERSON_ID);

    private final ApiContract contract = ApiContract.get();

    @Test
    void theFixturesSetEveryFieldAndConform() {
        assertThat(summary().propertyNames()).containsExactlyInAnyOrderElementsOf(SUMMARY_KEYS);
        assertThat(detail().propertyNames()).containsExactlyInAnyOrderElementsOf(
                Stream.concat(SUMMARY_KEYS.stream(), DETAIL_ONLY_KEYS.stream()).toList());
        assertThat(listViolations(item -> { })).isEmpty();
        assertThat(detailViolations(movie -> { })).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("summaryKeys")
    void aListItemWithoutASummaryFieldFails(String key) {
        assertThat(listViolations(item -> item.remove(key)))
                .singleElement().asString().startsWith(ITEM + ":").contains("'" + key + "'");
    }

    @ParameterizedTest
    @MethodSource("detailKeys")
    void aDetailWithoutAnyOfItsFieldsFails(String key) {
        assertThat(detailViolations(movie -> movie.remove(key)))
                .singleElement().asString().startsWith("$:").contains("'" + key + "'");
    }

    @Test
    void permittedNullsAndEmptyArraysConform() {
        Consumer<ObjectNode> nullSummaryFields = movie -> {
            movie.putNull("releaseYear");
            movie.putNull("posterUrl");
            movie.putArray("genres");
            movie.putNull("averageRating");
            movie.putNull("videoStatus");
        };
        assertThat(listViolations(nullSummaryFields)).isEmpty();
        assertThat(detailViolations(nullSummaryFields.andThen(movie -> {
            movie.putNull("maturityRating");
            movie.putArray("people");
            movie.putNull("userRating");
            movie.put("inWatchlist", false);
        }))).isEmpty();
    }

    @Test
    void requiredFieldsThatAreNotNullableRejectNull() {
        // A null enum value can fail both the type and the enum keyword, so every violation is
        // checked for its location instead of expecting exactly one.
        for (String key : List.of("id", "title", "slug", "description", "genres", "status")) {
            assertThat(listViolations(item -> item.putNull(key))).as(key)
                    .isNotEmpty().allSatisfy(violation -> assertThat(violation).startsWith(ITEM + "." + key));
        }
        for (String key : List.of("people", "inWatchlist")) {
            assertThat(detailViolations(movie -> movie.putNull(key))).as(key)
                    .isNotEmpty().allSatisfy(violation -> assertThat(violation).startsWith("$." + key));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "releaseYear, 1887", "releaseYear, 2101", "releaseYear, 0",
            "averageRating, 0", "averageRating, 0.99", "averageRating, 5.01", "averageRating, -1",
            "userRating, 0", "userRating, 6"
    })
    void valuesOutsideTheirDomainFail(String key, String value) {
        if (SUMMARY_KEYS.contains(key)) {
            assertThat(listViolations(item -> item.set(key, json(value))))
                    .singleElement().asString().startsWith(ITEM + "." + key);
        }
        assertThat(detailViolations(movie -> movie.set(key, json(value))))
                .singleElement().asString().startsWith("$." + key);
    }

    @ParameterizedTest
    @CsvSource({
            "releaseYear, 1888", "releaseYear, 2100",
            "averageRating, 1", "averageRating, 1.0", "averageRating, 3.25", "averageRating, 5",
            "userRating, 1", "userRating, 5"
    })
    void valuesAtTheBoundsOfTheirDomainConform(String key, String value) {
        if (SUMMARY_KEYS.contains(key)) {
            assertThat(listViolations(item -> item.set(key, json(value)))).isEmpty();
        }
        assertThat(detailViolations(movie -> movie.set(key, json(value)))).isEmpty();
    }

    static Stream<String> summaryKeys() {
        return SUMMARY_KEYS.stream();
    }

    static Stream<String> detailKeys() {
        return Stream.concat(SUMMARY_KEYS.stream(), DETAIL_ONLY_KEYS.stream());
    }

    private List<String> listViolations(Consumer<ObjectNode> change) {
        ObjectNode item = summary();
        change.accept(item);
        ObjectNode page = MAPPER.createObjectNode();
        page.putArray("items").add(item);
        page.set("page", json("{\"page\": 0, \"size\": 20, \"totalElements\": 1, \"totalPages\": 1}"));
        return violations(LIST_PATH, page);
    }

    private List<String> detailViolations(Consumer<ObjectNode> change) {
        ObjectNode movie = detail();
        change.accept(movie);
        return violations(DETAIL_PATH, movie);
    }

    private List<String> violations(String path, JsonNode body) {
        return contract.violations("GET", path, 200, "application/json", MAPPER.writeValueAsBytes(body));
    }

    private static ObjectNode summary() {
        return (ObjectNode) json(SUMMARY);
    }

    private static ObjectNode detail() {
        ObjectNode movie = summary();
        movie.setAll((ObjectNode) json(DETAIL_ONLY));
        return movie;
    }

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }
}
