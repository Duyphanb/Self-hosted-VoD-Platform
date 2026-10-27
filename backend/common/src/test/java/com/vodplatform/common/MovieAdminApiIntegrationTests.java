package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vodplatform.catalog.dto.MovieCreditInput;
import com.vodplatform.catalog.dto.MovieDetailResponse;
import com.vodplatform.catalog.dto.UpsertMovieRequest;
import com.vodplatform.catalog.persistence.CreditRole;
import com.vodplatform.catalog.persistence.Genre;
import com.vodplatform.catalog.persistence.GenreRepository;
import com.vodplatform.catalog.persistence.MaturityRating;
import com.vodplatform.catalog.persistence.Movie;
import com.vodplatform.catalog.persistence.MovieRepository;
import com.vodplatform.catalog.persistence.MovieStatus;
import com.vodplatform.catalog.persistence.Person;
import com.vodplatform.catalog.persistence.PersonRepository;
import com.vodplatform.catalog.web.MovieController;
import com.vodplatform.common.support.MovieVisibilityRule;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The admin movie writes ({@code POST /api/v1/movies}, {@code PUT} and {@code DELETE
 * /api/v1/movies/{movieId}}, Issue 3.5) through the assembled application: the bearer security
 * chain, the controller, the movie service and the H2 database (PostgreSQL mode). Every response is
 * also checked against the API contract.
 *
 * <p>Each test rolls back. The service therefore joins the test transaction and makes a single
 * attempt, and requests read the shared persistence context unless a test clears it; tests that
 * check the stored state clear it first. Concurrent writes, PostgreSQL constraints and row locks
 * are covered by {@code deploy/tests/catalog-runtime-smoke.py}.
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
class MovieAdminApiIntegrationTests {

    private static final String MOVIES = "/api/v1/movies";
    private static final String ADMIN_MOVIES = "/api/v1/admin/movies";
    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonComparator STRICT_WITH_ANY_TIMESTAMP = JsonAssert.comparator(new CustomComparator(
            JSONCompareMode.STRICT,
            new Customization("timestamp", (actual, expected) -> isInstant(actual))
    ));

    private static final UUID EXISTING_MOVIE = id("movie-existing");
    private static final UUID ACTION = id("genre-action");
    private static final UUID DIRECTOR = id("person-victor-vu");
    private static final UUID NAMESAKE_1 = id("person-le-van-nam-1");
    private static final UUID NAMESAKE_2 = id("person-le-van-nam-2");
    private static final UUID UNKNOWN_PERSON = id("person-unknown");

    private static final String NOT_NULL = "must not be null";
    private static final String NOT_BLANK = "must not be blank";
    private static final String CONTROL_CHARACTERS = "must not contain control characters";
    private static final String SLUG_PATTERN = "must match \\\"^[a-z0-9]+(-[a-z0-9]+)*$\\\"";

    /**
     * A valid body whose new genre and new person do not exist before the request. The validation
     * cases change one part of it.
     */
    private static final String VALID_BODY = """
            {
              "title": "Mắt Biếc", "slug": "mat-biec", "description": "A story of first love.",
              "releaseYear": 2019, "maturityRating": "T13", "genres": ["Tình Cảm"],
              "people": [{"name": "Trúc Anh", "role": "ACTOR"}], "status": "PUBLISHED"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MovieController movieController;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- authentication and authorization ----

    @Test
    void anonymousRequestsAreUnauthorized() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                json(post(MOVIES), VALID_BODY),
                json(post(MOVIES), "{"),
                json(put(MOVIES + "/" + UUID.randomUUID()), VALID_BODY),
                json(put(MOVIES + "/not-a-uuid"), "{}"),
                delete(MOVIES + "/" + UUID.randomUUID()),
                delete(MOVIES + "/not-a-uuid"))) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(apiError(401, "UNAUTHORIZED", "Authentication required or invalid token", null));
        }
    }

    @Test
    void roleUserIsForbiddenEvenForAnInvalidBodyAMissingMovieOrAMalformedId() throws Exception {
        persistPeopleGenresAndExistingMovie();
        String user = bearerToken("ROLE_USER");
        String existing = MOVIES + "/" + EXISTING_MOVIE;

        for (MockHttpServletRequestBuilder request : List.of(
                json(post(MOVIES), VALID_BODY),
                json(post(MOVIES), body(movie -> movie.put("title", "Mắt\u0000Biếc"))),
                json(post(MOVIES), body(movie -> movie.put("slug", "existing-movie"))),
                json(post(MOVIES), "{"),
                post(MOVIES),
                json(put(existing), VALID_BODY),
                json(put(existing), body(movie -> movie.remove("people"))),
                json(put(MOVIES + "/" + UUID.randomUUID()), VALID_BODY),
                json(put(MOVIES + "/not-a-uuid"), "{}"),
                delete(existing),
                delete(MOVIES + "/" + UUID.randomUUID()),
                delete(MOVIES + "/not-a-uuid"))) {
            perform(request, user)
                    .andExpect(status().isForbidden())
                    .andExpect(apiError(403, "FORBIDDEN", "Authenticated user is not allowed to perform this action", null));
        }

        clearPersistenceContext();
        assertThat(movieRepository.count()).isEqualTo(1);
        Movie movie = movieRepository.findById(EXISTING_MOVIE).orElseThrow();
        assertThat(movie.getTitle()).isEqualTo("Existing Movie");
        assertThat(movie.getStatus()).isEqualTo(MovieStatus.PUBLISHED);
    }

    @Test
    void methodSecurityRequiresTheAdminRoleWithoutTheUrlRule() {
        persistPeopleGenresAndExistingMovie();
        UpsertMovieRequest create = request("mat-biec");
        UpsertMovieRequest update = request("existing-movie");

        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user", null, "ROLE_USER"));
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() -> movieController.create(create));
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> movieController.update(EXISTING_MOVIE, update));
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() -> movieController.archive(EXISTING_MOVIE));
        assertThat(movieRepository.count()).isEqualTo(1);
        assertThat(movieRepository.findById(EXISTING_MOVIE).orElseThrow().getStatus()).isEqualTo(MovieStatus.PUBLISHED);

        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin", null, "ROLE_ADMIN"));
        ResponseEntity<MovieDetailResponse> created = movieController.create(create);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().slug()).isEqualTo("mat-biec");
        assertThat(movieController.update(EXISTING_MOVIE, update).title()).isEqualTo("Mắt Biếc");
        movieController.archive(EXISTING_MOVIE);
        assertThat(movieRepository.findById(EXISTING_MOVIE).orElseThrow().getStatus()).isEqualTo(MovieStatus.ARCHIVED);
    }

    // ---- create ----

    @Test
    void adminCreatesAMovieWithItsGenresAndCredits() throws Exception {
        persistPeopleGenresAndExistingMovie();
        String admin = bearerToken("ROLE_ADMIN");

        // "hành   động" reuses the existing genre and keeps its stored name; "VICTOR VŨ" matches the
        // stored person case-insensitively (ADR-009); "ignored" is ignored because personId is set.
        String created = perform(json(post(MOVIES), """
                {
                  "title": "  Mắt Biếc ", "slug": "mat-biec", "description": " Line one\\n\\tLine two ",
                  "releaseYear": 2019, "maturityRating": "T13", "genres": ["Tình   Cảm", "hành   động"],
                  "people": [
                    {"personId": "%s", "name": "ignored", "role": "DIRECTOR"},
                    {"name": "  Trúc   Anh ", "role": "ACTOR"},
                    {"name": "VICTOR VŨ", "role": "ACTOR"}
                  ],
                  "status": "PUBLISHED"
                }
                """.formatted(DIRECTOR)), admin)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID movieId = UUID.fromString(JsonPath.read(created, "$.id"));
        UUID newPerson = UUID.fromString(JsonPath.read(created, "$.people[1].personId"));
        String expected = """
                {
                  "id": "%s", "title": "Mắt Biếc", "slug": "mat-biec", "description": "Line one\\n\\tLine two",
                  "releaseYear": 2019, "posterUrl": null, "genres": ["Hành Động", "Tình Cảm"], "averageRating": null,
                  "status": "PUBLISHED", "videoStatus": null, "maturityRating": "T13",
                  "people": [
                    {"personId": "%s", "name": "Victor Vũ", "role": "DIRECTOR"},
                    {"personId": "%s", "name": "Trúc Anh", "role": "ACTOR"},
                    {"personId": "%s", "name": "Victor Vũ", "role": "ACTOR"}
                  ],
                  "userRating": null, "inWatchlist": false
                }
                """.formatted(movieId, DIRECTOR, newPerson, DIRECTOR);
        JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch(expected, created);

        clearPersistenceContext();
        perform(get(MOVIES + "/" + movieId), admin)
                .andExpect(status().isOk())
                .andExpect(content().json(expected, JsonCompareMode.STRICT));
        perform(get(MOVIES + "/" + movieId), bearerToken("ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(content().json(expected, JsonCompareMode.STRICT));
        assertThat(genreRepository.findAll()).extracting(Genre::getSlug).containsExactlyInAnyOrder("hanh-dong", "tinh-cam");
        assertThat(personRepository.findById(newPerson)).get().extracting(Person::getName, Person::getSlug)
                .containsExactly("Trúc Anh", "truc-anh");
    }

    @Test
    void aMovieWithOnlyTheRequiredFieldsHasNoOptionalValuesAndNoCredits() throws Exception {
        String admin = bearerToken("ROLE_ADMIN");
        String created = perform(json(post(MOVIES), body(movie -> {
            movie.remove("releaseYear");
            movie.remove("maturityRating");
            movie.putArray("people");
            movie.put("status", "DRAFT");
        })), admin)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch("""
                {
                  "id": "%s", "title": "Mắt Biếc", "slug": "mat-biec", "description": "A story of first love.",
                  "releaseYear": null, "posterUrl": null, "genres": ["Tình Cảm"], "averageRating": null,
                  "status": "DRAFT", "videoStatus": null, "maturityRating": null, "people": [],
                  "userRating": null, "inWatchlist": false
                }
                """.formatted((String) JsonPath.read(created, "$.id")), created);
    }

    // ---- update ----

    @Test
    void adminUpdatesAMovieAsAFullReplacement() throws Exception {
        persistPeopleGenresAndExistingMovie();
        String admin = bearerToken("ROLE_ADMIN");
        UUID movieId = create(admin, body(movie -> movie.putArray("people")
                .add(credit(DIRECTOR, "DIRECTOR"))
                .add(credit("Trúc Anh", "ACTOR"))));

        // Omitted optional fields become null; genres and credits are replaced in the new order.
        String updated = perform(json(put(MOVIES + "/" + movieId), """
                {
                  "title": "Mắt Biếc (Bản Đặc Biệt)", "slug": "mat-biec-ban-dac-biet", "description": "Updated.",
                  "genres": ["Hành Động"],
                  "people": [{"name": "Trúc Anh", "role": "DIRECTOR"}, {"personId": "%s", "role": "ACTOR"}],
                  "status": "DRAFT"
                }
                """.formatted(DIRECTOR)), admin)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID trucAnh = UUID.fromString(JsonPath.read(updated, "$.people[0].personId"));
        String expected = """
                {
                  "id": "%s", "title": "Mắt Biếc (Bản Đặc Biệt)", "slug": "mat-biec-ban-dac-biet",
                  "description": "Updated.", "releaseYear": null, "posterUrl": null, "genres": ["Hành Động"],
                  "averageRating": null, "status": "DRAFT", "videoStatus": null, "maturityRating": null,
                  "people": [
                    {"personId": "%s", "name": "Trúc Anh", "role": "DIRECTOR"},
                    {"personId": "%s", "name": "Victor Vũ", "role": "ACTOR"}
                  ],
                  "userRating": null, "inWatchlist": false
                }
                """.formatted(movieId, trucAnh, DIRECTOR);
        JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch(expected, updated);
        // The name-only credit reused the person the create made instead of adding another one.
        assertThat(personRepository.findAllByNameIgnoringCase("trúc anh")).extracting(Person::getId).containsExactly(trucAnh);

        clearPersistenceContext();
        perform(get(MOVIES + "/" + movieId), admin).andExpect(content().json(expected, JsonCompareMode.STRICT));

        // Keeping its own slug is not a conflict; an empty people list clears the credits.
        perform(json(put(MOVIES + "/" + movieId), body(movie -> {
            movie.put("slug", "mat-biec-ban-dac-biet");
            movie.putArray("people");
        })), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("mat-biec-ban-dac-biet"))
                .andExpect(jsonPath("$.people").isEmpty())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    // ---- validation ----

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                invalid("title missing", movie -> movie.remove("title"), fieldError("title", NOT_NULL)),
                invalid("blank title", movie -> movie.put("title", "   "), fieldError("title", NOT_BLANK)),
                invalid("NUL inside the title", movie -> movie.put("title", "Mắt\u0000Biếc"),
                        fieldError("title", CONTROL_CHARACTERS)),
                invalid("NUL at the end of the title", movie -> movie.put("title", "Mắt Biếc\u0000"),
                        fieldError("title", CONTROL_CHARACTERS)),
                invalid("tab in the title", movie -> movie.put("title", "Mắt\tBiếc"),
                        fieldError("title", CONTROL_CHARACTERS)),
                invalid("title too long", movie -> movie.put("title", "a".repeat(256)),
                        fieldError("title", "size must be between 1 and 255")),
                invalid("slug missing", movie -> movie.remove("slug"), fieldError("slug", NOT_NULL)),
                invalid("upper-case slug", movie -> movie.put("slug", "Mat-Biec"), fieldError("slug", SLUG_PATTERN)),
                invalid("slug with a double hyphen", movie -> movie.put("slug", "mat--biec"),
                        fieldError("slug", SLUG_PATTERN)),
                invalid("NUL in the slug", movie -> movie.put("slug", "mat\u0000biec"), fieldError("slug", SLUG_PATTERN)),
                invalid("slug too long", movie -> movie.put("slug", "a".repeat(256)),
                        fieldError("slug", "size must be between 1 and 255")),
                invalid("description missing", movie -> movie.remove("description"), fieldError("description", NOT_NULL)),
                invalid("NUL in the description", movie -> movie.put("description", "Line one\n\u0000"),
                        fieldError("description", CONTROL_CHARACTERS)),
                invalid("bell in the description", movie -> movie.put("description", "Ring\u0007"),
                        fieldError("description", CONTROL_CHARACTERS)),
                invalid("description too long", movie -> movie.put("description", "a".repeat(5001)),
                        fieldError("description", "size must be between 1 and 5000")),
                invalid("release year before 1888", movie -> movie.put("releaseYear", 1887),
                        fieldError("releaseYear", "must be between 1888 and 2100")),
                invalid("release year after 2100", movie -> movie.put("releaseYear", 2101),
                        fieldError("releaseYear", "must be between 1888 and 2100")),
                invalid("status missing", movie -> movie.remove("status"), fieldError("status", NOT_NULL)),
                invalid("genres missing", movie -> movie.remove("genres"), fieldError("genres", NOT_NULL)),
                invalid("no genre", movie -> movie.putArray("genres"),
                        fieldError("genres", "size must be between 1 and 10")),
                invalid("eleven genres", movie -> {
                    ArrayNode genres = movie.putArray("genres");
                    IntStream.rangeClosed(1, 11).forEach(index -> genres.add("Genre " + index));
                }, fieldError("genres", "size must be between 1 and 10")),
                invalid("null genre", movie -> movie.putArray("genres").addNull(), fieldError("genres[0]", NOT_NULL)),
                invalid("blank genre", movie -> movie.putArray("genres").add("Hài").add("   "),
                        fieldError("genres[1]", NOT_BLANK)),
                invalid("NUL in a genre", movie -> movie.putArray("genres").add("Tình\u0000Cảm"),
                        fieldError("genres[0]", CONTROL_CHARACTERS)),
                invalid("genre too long", movie -> movie.putArray("genres").add("a".repeat(101)),
                        fieldError("genres[0]", "size must be between 1 and 100")),
                invalid("genre without a letter or digit", movie -> movie.putArray("genres").add("!!!"),
                        fieldError("genres[0]", "must contain at least one letter or digit")),
                invalid("two genres with one slug", movie -> movie.putArray("genres").add("Hài").add(" HAI "),
                        fieldError("genres[1]", "must not name the same genre as another entry")),
                invalid("people missing", movie -> movie.remove("people"), fieldError("people", NOT_NULL)),
                invalid("fifty-one people", movie -> {
                    ArrayNode people = movie.putArray("people");
                    IntStream.rangeClosed(1, 51).forEach(index -> people.add(credit("Actor " + index, "ACTOR")));
                }, fieldError("people", "size must be between 0 and 50")),
                invalid("null credit", movie -> movie.putArray("people").addNull(), fieldError("people[0]", NOT_NULL)),
                invalid("credit without personId or name", movie -> movie.putArray("people")
                        .add(JSON.createObjectNode().put("role", "ACTOR")),
                        fieldError("people[0]", "must have a personId or a name")),
                invalid("credit without role", movie -> movie.putArray("people")
                        .add(JSON.createObjectNode().put("name", "Trúc Anh")),
                        fieldError("people[0].role", NOT_NULL)),
                invalid("NUL in a credit name", movie -> movie.putArray("people").add(credit("Trúc\u0000Anh", "ACTOR")),
                        fieldError("people[0].name", CONTROL_CHARACTERS)),
                invalid("credit name too long", movie -> movie.putArray("people").add(credit("a".repeat(256), "ACTOR")),
                        fieldError("people[0].name", "size must be between 1 and 255")),
                invalid("unknown personId", movie -> movie.putArray("people").add(credit(UNKNOWN_PERSON, "ACTOR")),
                        fieldError("people[0].personId", "must identify an existing person")),
                invalid("name shared by two people", movie -> movie.putArray("people").add(credit("lê văn nam", "ACTOR")),
                        fieldError("people[0].personId", "is required because more than one person has this name")),
                invalid("same person and role twice", movie -> movie.putArray("people")
                        .add(credit(DIRECTOR, "DIRECTOR"))
                        .add(credit("VICTOR VŨ", "DIRECTOR")),
                        fieldError("people[1]", "must not repeat the person and role of another entry")),
                invalid("several problems", movie -> {
                    movie.put("title", " ");
                    movie.put("slug", "Bad Slug");
                    movie.put("releaseYear", 1800);
                    movie.remove("status");
                }, fieldError("releaseYear", "must be between 1888 and 2100") + ", " + fieldError("slug", SLUG_PATTERN)
                        + ", " + fieldError("status", NOT_NULL) + ", " + fieldError("title", NOT_BLANK)),
                // Validation comes before the slug check, so an invalid body never reveals a conflict.
                invalid("invalid body with the slug of another movie", movie -> {
                    movie.put("slug", "existing-movie");
                    movie.put("title", "Bad\u0000Title");
                }, fieldError("title", CONTROL_CHARACTERS))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void invalidBodiesAreValidationErrorsAndWriteNothing(String name, String body, String fieldErrors) throws Exception {
        persistPeopleGenresAndExistingMovie();
        String admin = bearerToken("ROLE_ADMIN");
        long genres = genreRepository.count();
        long people = personRepository.count();

        perform(json(post(MOVIES), body), admin)
                .andExpect(status().isBadRequest())
                .andExpect(apiError(400, "VALIDATION_ERROR", "Request validation failed", fieldErrors));
        perform(json(put(MOVIES + "/" + EXISTING_MOVIE), body), admin)
                .andExpect(status().isBadRequest())
                .andExpect(apiError(400, "VALIDATION_ERROR", "Request validation failed", fieldErrors));

        clearPersistenceContext();
        assertThat(movieRepository.count()).isEqualTo(1);
        assertThat(genreRepository.count()).isEqualTo(genres);
        assertThat(personRepository.count()).isEqualTo(people);
        Movie existing = movieRepository.findById(EXISTING_MOVIE).orElseThrow();
        assertThat(existing.getTitle()).isEqualTo("Existing Movie");
        assertThat(existing.getGenres()).extracting(Genre::getSlug).containsExactly("hanh-dong");
    }

    static Stream<Arguments> malformedBodies() {
        return Stream.of(
                Arguments.of("truncated JSON", "{"),
                Arguments.of("no body", ""),
                Arguments.of("JSON null", "null"),
                Arguments.of("array instead of an object", "[]"),
                Arguments.of("status that is not a MovieStatus", body(movie -> movie.put("status", "draft"))),
                Arguments.of("excluded maturity rating C", body(movie -> movie.put("maturityRating", "C"))),
                Arguments.of("role that is not a credit role", body(movie -> movie.putArray("people")
                        .add(credit("Trúc Anh", "WRITER")))),
                Arguments.of("personId that is not a UUID", body(movie -> movie.putArray("people")
                        .add(JSON.createObjectNode().put("personId", "not-a-uuid").put("role", "ACTOR")))),
                Arguments.of("personId with surrounding spaces", body(movie -> movie.putArray("people")
                        .add(JSON.createObjectNode().put("personId", " " + DIRECTOR + " ").put("role", "ACTOR")))),
                Arguments.of("genres as a single string", body(movie -> movie.put("genres", "Hài"))),
                // releaseYear is an integer: a number with a fraction is rejected, never truncated.
                Arguments.of("release year with a fraction", body(movie -> movie.put("releaseYear", 2019.5))),
                Arguments.of("release year written as a decimal", body(movie -> movie.put("releaseYear", 2019.0))),
                Arguments.of("release year that is a boolean", body(movie -> movie.put("releaseYear", true)))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedBodies")
    void unreadableBodiesAreMalformedRequestsAndWriteNothing(String name, String body) throws Exception {
        persistPeopleGenresAndExistingMovie();
        String admin = bearerToken("ROLE_ADMIN");

        perform(json(post(MOVIES), body), admin)
                .andExpect(status().isBadRequest())
                .andExpect(apiError(400, "MALFORMED_REQUEST", "Request body is missing or malformed", null));
        perform(json(put(MOVIES + "/" + EXISTING_MOVIE), body), admin)
                .andExpect(status().isBadRequest())
                .andExpect(apiError(400, "MALFORMED_REQUEST", "Request body is missing or malformed", null));

        clearPersistenceContext();
        assertThat(movieRepository.count()).isEqualTo(1);
        assertThat(movieRepository.findById(EXISTING_MOVIE).orElseThrow().getTitle()).isEqualTo("Existing Movie");
    }

    @Test
    void aMovieIdThatIsNotACanonicalUuidIsAValidationError() throws Exception {
        String admin = bearerToken("ROLE_ADMIN");
        for (String movieId : List.of("not-a-uuid", "a-b-c-d-e", "0000000a-000b-000c-000d-0000000000e")) {
            perform(json(put(MOVIES + "/" + movieId), VALID_BODY), admin)
                    .andExpect(status().isBadRequest())
                    .andExpect(validationError("movieId", "must be a valid UUID"));
            perform(delete(MOVIES + "/" + movieId), admin)
                    .andExpect(status().isBadRequest())
                    .andExpect(validationError("movieId", "must be a valid UUID"));
        }
    }

    // ---- not found and conflict ----

    @Test
    void writesToAMissingMovieAreNotFound() throws Exception {
        String admin = bearerToken("ROLE_ADMIN");
        UUID missing = UUID.randomUUID();

        perform(json(put(MOVIES + "/" + missing), VALID_BODY), admin)
                .andExpect(status().isNotFound())
                .andExpect(apiError(404, "MOVIE_NOT_FOUND", "Movie not found", null));
        perform(delete(MOVIES + "/" + missing), admin)
                .andExpect(status().isNotFound())
                .andExpect(apiError(404, "MOVIE_NOT_FOUND", "Movie not found", null));
        assertThat(movieRepository.count()).isZero();
        assertThat(genreRepository.count()).isZero();
        assertThat(personRepository.count()).isZero();
    }

    @Test
    void aSlugUsedByAnotherMovieInAnyStatusIsAConflict() throws Exception {
        persistPeopleGenresAndExistingMovie();
        movieRepository.save(new Movie(id("movie-archived"), "Archived Movie", "archived-movie", "Archived", null,
                null, MovieStatus.ARCHIVED, T0.plusSeconds(1)));
        clearPersistenceContext();
        String admin = bearerToken("ROLE_ADMIN");
        long genres = genreRepository.count();
        long people = personRepository.count();

        for (String slug : List.of("existing-movie", "archived-movie")) {
            perform(json(post(MOVIES), body(movie -> movie.put("slug", slug))), admin)
                    .andExpect(status().isConflict())
                    .andExpect(apiError(409, "MOVIE_SLUG_CONFLICT", "Slug is already used by another movie", null));
        }
        UUID movieId = create(admin, VALID_BODY);
        perform(json(put(MOVIES + "/" + movieId), body(movie -> {
            movie.put("slug", "archived-movie");
            movie.putArray("genres").add("Kinh Dị");
        })), admin)
                .andExpect(status().isConflict())
                .andExpect(apiError(409, "MOVIE_SLUG_CONFLICT", "Slug is already used by another movie", null));

        clearPersistenceContext();
        // The conflicts wrote nothing: only the created movie and its new genre and person were added.
        assertThat(movieRepository.count()).isEqualTo(3);
        assertThat(genreRepository.count()).isEqualTo(genres + 1);
        assertThat(personRepository.count()).isEqualTo(people + 1);
        perform(get(MOVIES + "/" + movieId), admin)
                .andExpect(jsonPath("$.slug").value("mat-biec"))
                .andExpect(jsonPath("$.genres").value(contains("Tình Cảm")));
    }

    // ---- archive and visibility ----

    @Test
    void archiveIsASoftDeleteThatIsIdempotentAndKeepsGenresAndCredits() throws Exception {
        persistPeopleGenresAndExistingMovie();
        String admin = bearerToken("ROLE_ADMIN");
        UUID movieId = create(admin, body(movie -> movie.putArray("people")
                .add(credit(DIRECTOR, "DIRECTOR"))
                .add(credit("Trúc Anh", "ACTOR"))));
        clearPersistenceContext();
        ObjectNode before = (ObjectNode) JSON.readTree(perform(get(MOVIES + "/" + movieId), admin)
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        perform(delete(MOVIES + "/" + movieId), admin)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        clearPersistenceContext();
        Instant archivedAt = movieRepository.findById(movieId).orElseThrow().getUpdatedAt();

        perform(delete(MOVIES + "/" + movieId), admin)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        clearPersistenceContext();
        Movie archived = movieRepository.findById(movieId).orElseThrow();
        assertThat(archived.getStatus()).isEqualTo(MovieStatus.ARCHIVED);
        assertThat(archived.getUpdatedAt()).as("archiving an archived movie changes nothing").isEqualTo(archivedAt);

        before.put("status", "ARCHIVED");
        perform(get(MOVIES + "/" + movieId), admin)
                .andExpect(status().isOk())
                .andExpect(content().json(JSON.writeValueAsString(before), JsonCompareMode.STRICT));

        // A draft is archived the same way.
        UUID draft = create(admin, body(movie -> {
            movie.put("slug", "draft-movie");
            movie.put("status", "DRAFT");
        }));
        perform(delete(MOVIES + "/" + draft), admin).andExpect(status().isNoContent());
        clearPersistenceContext();
        assertThat(movieRepository.findById(draft).orElseThrow().getStatus()).isEqualTo(MovieStatus.ARCHIVED);
    }

    @Test
    void anArchivedMovieIsHiddenFromNonAdminsExactlyLikeAMissingOne() throws Exception {
        MovieVisibilityRule.Fixtures fixtures = MovieVisibilityRule.persistFixtures(movieRepository);
        String admin = bearerToken("ROLE_ADMIN");
        String user = bearerToken("ROLE_USER");
        UUID movieId = create(admin, VALID_BODY);
        perform(get(MOVIES + "/" + movieId), user).andExpect(status().isOk());

        perform(delete(MOVIES + "/" + movieId), admin).andExpect(status().isNoContent());
        clearPersistenceContext();

        MovieVisibilityRule.assertHiddenLikeMissing(
                new MovieVisibilityRule.Fixtures(fixtures.published(), fixtures.draft(), movieId, fixtures.missing()),
                id -> perform(get(MOVIES + "/" + id), user));
        perform(get(MOVIES + "/" + movieId), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        for (String caller : List.of(user, admin)) {
            perform(get(MOVIES), caller)
                    .andExpect(jsonPath("$.items[*].id").value(contains(fixtures.published().toString())));
        }
        perform(get(ADMIN_MOVIES + "?status=ARCHIVED"), admin)
                .andExpect(jsonPath("$.items[*].id")
                        .value(containsInAnyOrder(movieId.toString(), fixtures.archived().toString())));
    }

    @Test
    void aMovieWrittenAsDraftOrArchivedThroughPutIsHiddenAndPublishingRestoresIt() throws Exception {
        MovieVisibilityRule.Fixtures fixtures = MovieVisibilityRule.persistFixtures(movieRepository);
        String admin = bearerToken("ROLE_ADMIN");
        String user = bearerToken("ROLE_USER");
        UUID movieId = create(admin, body(movie -> movie.put("status", "DRAFT")));
        clearPersistenceContext();

        MovieVisibilityRule.assertHiddenLikeMissing(
                new MovieVisibilityRule.Fixtures(fixtures.published(), movieId, fixtures.archived(), fixtures.missing()),
                id -> perform(get(MOVIES + "/" + id), user));
        perform(get(MOVIES + "/" + movieId), admin).andExpect(jsonPath("$.status").value("DRAFT"));

        perform(json(put(MOVIES + "/" + movieId), body(movie -> movie.put("status", "ARCHIVED"))), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        clearPersistenceContext();
        MovieVisibilityRule.assertHiddenLikeMissing(
                new MovieVisibilityRule.Fixtures(fixtures.published(), fixtures.draft(), movieId, fixtures.missing()),
                id -> perform(get(MOVIES + "/" + id), user));

        perform(json(put(MOVIES + "/" + movieId), VALID_BODY), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        clearPersistenceContext();
        perform(get(MOVIES + "/" + movieId), user)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        perform(get(MOVIES), user)
                .andExpect(jsonPath("$.items[*].id")
                        .value(containsInAnyOrder(movieId.toString(), fixtures.published().toString())));
    }

    // ---- fixtures ----

    /**
     * The genre Hành Động, the person Victor Vũ, two people named Lê Văn Nam, and a published movie
     * in Hành Động.
     */
    private void persistPeopleGenresAndExistingMovie() {
        Genre action = genreRepository.save(new Genre(ACTION, "Hành Động", "hanh-dong"));
        personRepository.save(new Person(DIRECTOR, "Victor Vũ", "victor-vu"));
        personRepository.save(new Person(NAMESAKE_1, "Lê Văn Nam", "le-van-nam"));
        personRepository.save(new Person(NAMESAKE_2, "Lê Văn Nam", "le-van-nam-2"));
        Movie existing = new Movie(EXISTING_MOVIE, "Existing Movie", "existing-movie", "Already in the catalog", 2020,
                null, MovieStatus.PUBLISHED, T0);
        existing.addGenre(action);
        movieRepository.save(existing);
        clearPersistenceContext();
    }

    /** Writes pending changes and detaches every entity, so the next request reads the database. */
    private void clearPersistenceContext() {
        entityManager.flush();
        entityManager.clear();
    }

    private UUID create(String admin, String body) throws Exception {
        String created = perform(json(post(MOVIES), body), admin)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(JsonPath.read(created, "$.id"));
    }

    private static UpsertMovieRequest request(String slug) {
        return new UpsertMovieRequest("Mắt Biếc", slug, "A story of first love.", 2019, MaturityRating.T13,
                List.of("Tình Cảm"), List.of(new MovieCreditInput(DIRECTOR, null, CreditRole.DIRECTOR)),
                MovieStatus.DRAFT);
    }

    private static Arguments invalid(String name, Consumer<ObjectNode> change, String fieldErrors) {
        return Arguments.of(name, body(change), fieldErrors);
    }

    /** {@link #VALID_BODY} after the change. */
    private static String body(Consumer<ObjectNode> change) {
        ObjectNode movie = (ObjectNode) JSON.readTree(VALID_BODY);
        change.accept(movie);
        return JSON.writeValueAsString(movie);
    }

    private static ObjectNode credit(String name, String role) {
        return JSON.createObjectNode().put("name", name).put("role", role);
    }

    private static ObjectNode credit(UUID personId, String role) {
        return JSON.createObjectNode().put("personId", personId.toString()).put("role", role);
    }

    private static String fieldError(String field, String message) {
        return "{\"field\": \"%s\", \"message\": \"%s\"}".formatted(field, message);
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    // ---- helpers ----

    private ResultActions perform(MockHttpServletRequestBuilder request, String authorization) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, authorization));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8));
    }

    private static ResultMatcher validationError(String field, String message) {
        return apiError(400, "VALIDATION_ERROR", "Request validation failed", fieldError(field, message));
    }

    private static ResultMatcher apiError(int status, String code, String message, String fieldErrors) {
        String errors = fieldErrors == null ? "" : ", \"fieldErrors\": [" + fieldErrors + "]";
        return content().json("""
                {
                  "timestamp": "any instant",
                  "status": %d,
                  "code": "%s",
                  "message": "%s"%s
                }
                """.formatted(status, code, message, errors), STRICT_WITH_ANY_TIMESTAMP);
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
                .claim("email", "movie-admin-api-test@example.com")
                .claim("roles", List.of(role))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims
        )).getTokenValue();
        return "Bearer " + token;
    }
}
