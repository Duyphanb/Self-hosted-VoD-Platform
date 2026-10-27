package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vodplatform.catalog.persistence.CreditRole;
import com.vodplatform.catalog.persistence.Genre;
import com.vodplatform.catalog.persistence.GenreRepository;
import com.vodplatform.catalog.persistence.MaturityRating;
import com.vodplatform.catalog.persistence.Movie;
import com.vodplatform.catalog.persistence.MovieRepository;
import com.vodplatform.catalog.persistence.MovieSort;
import com.vodplatform.catalog.persistence.MovieStatus;
import com.vodplatform.catalog.persistence.Person;
import com.vodplatform.catalog.persistence.PersonRepository;
import com.vodplatform.catalog.web.MovieAdminController;
import com.vodplatform.common.support.MovieVisibilityRule;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/v1/movies}, {@code GET /api/v1/admin/movies} and
 * {@code GET /api/v1/movies/{movieId}} through the assembled application: the bearer security
 * chain, the controllers, the movie service and the H2 database (PostgreSQL mode). Each test rolls
 * back; the fixtures are written directly with the catalog repositories, so creation times and
 * therefore the {@code latest} order are fixed.
 *
 * <p>The order of Vietnamese titles under {@code sort=title} depends on the database collation
 * (#140), so the title order is checked with ASCII titles only.
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
class MovieApiIntegrationTests {

    private static final String MOVIES = "/api/v1/movies";
    private static final String ADMIN_MOVIES = "/api/v1/admin/movies";
    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");
    private static final JsonComparator STRICT_WITH_ANY_TIMESTAMP = JsonAssert.comparator(new CustomComparator(
            JSONCompareMode.STRICT,
            new Customization("timestamp", (actual, expected) -> isInstant(actual))
    ));

    private static final UUID BRIGHT_DAYS = id("movie-bright-days");
    private static final UUID APPLE_STORY = id("movie-apple-story");
    private static final UUID COLD_RAIN = id("movie-cold-rain");
    private static final UUID DRAFT_MOVIE = id("movie-draft");
    private static final UUID ARCHIVED_MOVIE = id("movie-archived");
    private static final UUID DIRECTOR = id("person-victor-vu");
    private static final UUID ACTOR = id("person-kaity-nguyen");

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
    private MovieAdminController movieAdminController;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- authentication and authorization ----

    @Test
    void anonymousRequestsAreUnauthorized() throws Exception {
        for (String path : List.of(
                MOVIES,
                MOVIES + "?size=0",
                ADMIN_MOVIES,
                MOVIES + "/" + UUID.randomUUID(),
                MOVIES + "/not-a-uuid")) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(apiError(401, "UNAUTHORIZED", "Authentication required or invalid token", null));
        }
    }

    @Test
    void roleUserIsForbiddenFromTheAdminListEvenWithInvalidParameters() throws Exception {
        String user = bearerToken("ROLE_USER");
        for (String query : List.of("", "?status=DRAFT", "?status=draft", "?size=0", "?sort=newest")) {
            perform(ADMIN_MOVIES + query, user)
                    .andExpect(status().isForbidden())
                    .andExpect(apiError(403, "FORBIDDEN", "Authenticated user is not allowed to perform this action", null));
        }
    }

    @Test
    void methodSecurityRequiresTheAdminRoleWithoutTheUrlRule() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user", null, "ROLE_USER"));
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> movieAdminController.list(null, 0, 20, MovieSort.LATEST));

        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin", null, "ROLE_ADMIN"));
        assertThat(movieAdminController.list(null, 0, 20, MovieSort.LATEST).items()).isEmpty();
    }

    // ---- public list ----

    @Test
    void listReturnsPublishedMoviesOnlyInStablePagesForEveryRole() throws Exception {
        persistCatalog();

        for (String role : List.of("ROLE_USER", "ROLE_ADMIN")) {
            String caller = bearerToken(role);
            perform(MOVIES + "?page=0&size=2", caller)
                    .andExpect(status().isOk())
                    .andExpect(content().json(page(0, 2, 3, 2, coldRain(), appleStory()), JsonCompareMode.STRICT));
            perform(MOVIES + "?page=1&size=2", caller)
                    .andExpect(status().isOk())
                    .andExpect(content().json(page(1, 2, 3, 2, brightDays()), JsonCompareMode.STRICT));
            perform(MOVIES, caller)
                    .andExpect(status().isOk())
                    .andExpect(content().json(page(0, 20, 3, 1, coldRain(), appleStory(), brightDays()),
                            JsonCompareMode.STRICT));
            perform(MOVIES + "?page=5", caller)
                    .andExpect(status().isOk())
                    .andExpect(content().json(page(5, 20, 3, 1), JsonCompareMode.STRICT));
            // The list declares no status parameter: an undeclared parameter is ignored, never a filter.
            perform(MOVIES + "?status=DRAFT", caller)
                    .andExpect(status().isOk())
                    .andExpect(content().json(page(0, 20, 3, 1, coldRain(), appleStory(), brightDays()),
                            JsonCompareMode.STRICT));
        }
    }

    @Test
    void listSortsByLatestByDefaultOrByTitleIgnoringCase() throws Exception {
        persistCatalog();
        String user = bearerToken("ROLE_USER");

        perform(MOVIES + "?sort=latest", user)
                .andExpect(jsonPath("$.items[*].id").value(contains(ids(COLD_RAIN, APPLE_STORY, BRIGHT_DAYS))));
        perform(MOVIES + "?sort=title", user)
                .andExpect(jsonPath("$.items[*].id").value(contains(ids(APPLE_STORY, BRIGHT_DAYS, COLD_RAIN))));
        perform(MOVIES + "?sort=title&size=1&page=2", user)
                .andExpect(content().json(page(2, 1, 3, 3, coldRain()), JsonCompareMode.STRICT));
    }

    @Test
    void listFiltersByGenreNameOrSlugAndByReleaseYear() throws Exception {
        persistCatalog();
        String user = bearerToken("ROLE_USER");

        perform(MOVIES + "?genre=Hài", user)
                .andExpect(jsonPath("$.items[*].id").value(contains(ids(APPLE_STORY, BRIGHT_DAYS))));
        // The draft movie is in the genre too, but the list holds published movies only.
        perform(MOVIES + "?genre=  HÀNH   động ", user)
                .andExpect(content().json(page(0, 20, 1, 1, brightDays()), JsonCompareMode.STRICT));
        perform(MOVIES + "?genre=hanh-dong", bearerToken("ROLE_ADMIN"))
                .andExpect(jsonPath("$.items[*].id").value(contains(ids(BRIGHT_DAYS))));
        perform(MOVIES + "?year=2023", user)
                .andExpect(jsonPath("$.items[*].id").value(contains(ids(APPLE_STORY))));
        perform(MOVIES + "?genre=hai&year=2024&sort=title", user)
                .andExpect(jsonPath("$.items[*].id").value(contains(ids(BRIGHT_DAYS))));
        perform(MOVIES + "?genre=Hành Động&year=2023", user)
                .andExpect(content().json(page(0, 20, 0, 0), JsonCompareMode.STRICT));
        perform(MOVIES + "?genre=Kinh dị", user)
                .andExpect(status().isOk())
                .andExpect(content().json(page(0, 20, 0, 0), JsonCompareMode.STRICT));
    }

    // ---- admin list ----

    @Test
    void adminListReturnsEveryStatusOrTheRequestedOne() throws Exception {
        persistCatalog();
        String admin = bearerToken("ROLE_ADMIN");

        perform(ADMIN_MOVIES, admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id")
                        .value(contains(ids(ARCHIVED_MOVIE, DRAFT_MOVIE, COLD_RAIN, APPLE_STORY, BRIGHT_DAYS))))
                .andExpect(jsonPath("$.items[*].status")
                        .value(contains("ARCHIVED", "DRAFT", "PUBLISHED", "PUBLISHED", "PUBLISHED")))
                .andExpect(jsonPath("$.page.totalElements").value(5));
        perform(ADMIN_MOVIES + "?status=DRAFT", admin)
                .andExpect(content().json(page(0, 20, 1, 1, """
                        {
                          "id": "%s", "title": "Draft Movie", "slug": "draft-movie", "description": "Draft",
                          "releaseYear": null, "posterUrl": null, "genres": ["Hành Động"], "averageRating": null,
                          "status": "DRAFT", "videoStatus": null
                        }
                        """.formatted(DRAFT_MOVIE)), JsonCompareMode.STRICT));
        perform(ADMIN_MOVIES + "?status=ARCHIVED", admin)
                .andExpect(jsonPath("$.items[*].id").value(contains(ids(ARCHIVED_MOVIE))));
        perform(ADMIN_MOVIES + "?status=PUBLISHED&sort=title&page=0&size=2", admin)
                .andExpect(content().json(page(0, 2, 3, 2, appleStory(), brightDays()), JsonCompareMode.STRICT));
    }

    // ---- list parameters ----

    static Stream<Arguments> invalidListParameters() {
        return Stream.of(
                Arguments.of(MOVIES + "?page=-1", "page", "must be between 0 and 10000"),
                Arguments.of(MOVIES + "?page=10001", "page", "must be between 0 and 10000"),
                Arguments.of(MOVIES + "?page=first", "page", "must be an integer"),
                Arguments.of(MOVIES + "?size=0", "size", "must be between 1 and 100"),
                Arguments.of(MOVIES + "?size=101", "size", "must be between 1 and 100"),
                Arguments.of(MOVIES + "?size=ten", "size", "must be an integer"),
                Arguments.of(MOVIES + "?sort=newest", "sort", "is not an allowed value"),
                Arguments.of(MOVIES + "?sort=LATEST", "sort", "is not an allowed value"),
                // Spring Data sort syntax: the list binds only the contract's sort values.
                Arguments.of(MOVIES + "?sort=title,desc", "sort", "is not an allowed value"),
                Arguments.of(MOVIES + "?sort=createdAt", "sort", "is not an allowed value"),
                Arguments.of(MOVIES + "?year=1887", "year", "must be between 1888 and 2100"),
                Arguments.of(MOVIES + "?year=2101", "year", "must be between 1888 and 2100"),
                Arguments.of(MOVIES + "?year=2024.5", "year", "must be an integer"),
                Arguments.of(MOVIES + "?genre=", "genre", "size must be between 1 and 120"),
                Arguments.of(MOVIES + "?genre=" + "a".repeat(121), "genre", "size must be between 1 and 120"),
                Arguments.of(MOVIES + "?genre=!!!", "genre", "must contain at least one letter or digit"),
                // Spaces only; MockMvc encodes the URI template, so the spaces are written as such.
                Arguments.of(MOVIES + "?genre=   &size=5", "genre", "must contain at least one letter or digit"),
                Arguments.of(ADMIN_MOVIES + "?page=10001", "page", "must be between 0 and 10000"),
                Arguments.of(ADMIN_MOVIES + "?size=101", "size", "must be between 1 and 100"),
                Arguments.of(ADMIN_MOVIES + "?sort=title,asc", "sort", "is not an allowed value"),
                Arguments.of(ADMIN_MOVIES + "?status=draft", "status", "is not an allowed value"),
                Arguments.of(ADMIN_MOVIES + "?status=DELETED", "status", "is not an allowed value")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidListParameters")
    void invalidListParametersAreValidationErrors(String request, String field, String message) throws Exception {
        perform(request, bearerToken("ROLE_ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(validationError(field, message));
    }

    @Test
    void listParametersAtTheContractLimitsAreAccepted() throws Exception {
        persistCatalog();
        String user = bearerToken("ROLE_USER");

        perform(MOVIES + "?page=10000&size=100", user)
                .andExpect(content().json(page(10000, 100, 3, 1), JsonCompareMode.STRICT));
        perform(ADMIN_MOVIES + "?page=10000&size=100", bearerToken("ROLE_ADMIN"))
                .andExpect(content().json(page(10000, 100, 5, 1), JsonCompareMode.STRICT));
        perform(MOVIES + "?size=1", user)
                .andExpect(jsonPath("$.items[*].id").value(contains(ids(COLD_RAIN))));
        perform(MOVIES + "?year=1888", user).andExpect(status().isOk());
        perform(MOVIES + "?year=2100", user).andExpect(status().isOk());
        perform(MOVIES + "?genre=" + "a".repeat(120), user).andExpect(status().isOk());
    }

    // ---- detail ----

    @Test
    void detailReturnsTheMovieWithItsGenresAndCreditsAndNoDataOfLaterModules() throws Exception {
        persistCatalog();
        String user = bearerToken("ROLE_USER");

        perform(MOVIES + "/" + BRIGHT_DAYS, user)
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "id": "%s",
                          "title": "Bright Days",
                          "slug": "bright-days",
                          "description": "First of three",
                          "releaseYear": 2024,
                          "posterUrl": null,
                          "genres": ["Hài", "Hành Động"],
                          "averageRating": null,
                          "status": "PUBLISHED",
                          "videoStatus": null,
                          "maturityRating": "T13",
                          "people": [
                            {"personId": "%s", "name": "Victor Vũ", "role": "DIRECTOR"},
                            {"personId": "%s", "name": "Kaity Nguyễn", "role": "ACTOR"}
                          ],
                          "userRating": null,
                          "inWatchlist": false
                        }
                        """.formatted(BRIGHT_DAYS, DIRECTOR, ACTOR), JsonCompareMode.STRICT));
        perform(MOVIES + "/" + APPLE_STORY, user)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maturityRating").isEmpty())
                .andExpect(jsonPath("$.people").isEmpty())
                .andExpect(jsonPath("$.genres").value(contains("Hài")))
                .andExpect(jsonPath("$.playbackProgress").doesNotExist());
    }

    @Test
    void nonAdminsGetTheSameNotFoundForDraftArchivedAndMissingMovies() throws Exception {
        MovieVisibilityRule.Fixtures movies = MovieVisibilityRule.persistFixtures(movieRepository);
        String user = bearerToken("ROLE_USER");

        perform(MOVIES + "/" + movies.published(), user)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        MovieVisibilityRule.assertHiddenLikeMissing(movies, movieId -> perform(MOVIES + "/" + movieId, user));
    }

    @Test
    void adminsReadMoviesInEveryStatusButNotMissingOnes() throws Exception {
        MovieVisibilityRule.Fixtures movies = MovieVisibilityRule.persistFixtures(movieRepository);
        String admin = bearerToken("ROLE_ADMIN");

        perform(MOVIES + "/" + movies.draft(), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(movies.draft().toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"));
        perform(MOVIES + "/" + movies.archived(), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        perform(MOVIES + "/" + movies.missing(), admin)
                .andExpect(status().isNotFound())
                .andExpect(apiError(404, "MOVIE_NOT_FOUND", "Movie not found", null));
    }

    @Test
    void theVisibilityRuleFailsWhenAHiddenMovieIsVisible() {
        MovieVisibilityRule.Fixtures movies = MovieVisibilityRule.persistFixtures(movieRepository);
        String admin = bearerToken("ROLE_ADMIN");

        // Admins may read hidden movies on this endpoint, so the rule's assertion must fail for them.
        assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> MovieVisibilityRule.assertHiddenLikeMissing(
                movies, movieId -> perform(MOVIES + "/" + movieId, admin)));
    }

    @Test
    void aMovieIdThatIsNotAUuidIsAValidationErrorForEveryRole() throws Exception {
        for (String role : List.of("ROLE_USER", "ROLE_ADMIN")) {
            perform(MOVIES + "/not-a-uuid", bearerToken(role))
                    .andExpect(status().isBadRequest())
                    .andExpect(validationError("movieId", "must be a valid UUID"));
        }
    }

    // ---- fixtures ----

    /**
     * Three published movies, created one second apart in the order Bright Days, apple Story,
     * Cold Rain, then a draft and an archived movie.
     */
    private void persistCatalog() {
        Genre action = genreRepository.save(new Genre(id("genre-action"), "Hành Động", "hanh-dong"));
        Genre comedy = genreRepository.save(new Genre(id("genre-comedy"), "Hài", "hai"));
        Person director = personRepository.save(new Person(DIRECTOR, "Victor Vũ", "victor-vu"));
        Person actor = personRepository.save(new Person(ACTOR, "Kaity Nguyễn", "kaity-nguyen"));

        Movie brightDays = movie(BRIGHT_DAYS, "Bright Days", "bright-days", "First of three", 2024,
                MaturityRating.T13, MovieStatus.PUBLISHED, 1);
        brightDays.addGenre(action);
        brightDays.addGenre(comedy);
        brightDays.addCredit(director, CreditRole.DIRECTOR, 0);
        brightDays.addCredit(actor, CreditRole.ACTOR, 1);
        movieRepository.save(brightDays);

        Movie appleStory = movie(APPLE_STORY, "apple Story", "apple-story", "Second of three", 2023,
                null, MovieStatus.PUBLISHED, 2);
        appleStory.addGenre(comedy);
        movieRepository.save(appleStory);

        movieRepository.save(movie(COLD_RAIN, "Cold Rain", "cold-rain", "Third of three", null,
                MaturityRating.P, MovieStatus.PUBLISHED, 3));

        Movie draft = movie(DRAFT_MOVIE, "Draft Movie", "draft-movie", "Draft", null, null, MovieStatus.DRAFT, 4);
        draft.addGenre(action);
        movieRepository.save(draft);

        movieRepository.save(movie(ARCHIVED_MOVIE, "Archived Movie", "archived-movie", "Archived", 2020,
                null, MovieStatus.ARCHIVED, 5));

        // Requests then read from the database, not from instances already in the persistence context.
        entityManager.flush();
        entityManager.clear();
    }

    private static Movie movie(UUID id, String title, String slug, String description, Integer releaseYear,
            MaturityRating maturityRating, MovieStatus status, int createdSecond) {
        return new Movie(id, title, slug, description, releaseYear, maturityRating, status,
                T0.plusSeconds(createdSecond));
    }

    private static String brightDays() {
        return summary(BRIGHT_DAYS, "Bright Days", "bright-days", "First of three", "2024", "[\"Hài\", \"Hành Động\"]");
    }

    private static String appleStory() {
        return summary(APPLE_STORY, "apple Story", "apple-story", "Second of three", "2023", "[\"Hài\"]");
    }

    private static String coldRain() {
        return summary(COLD_RAIN, "Cold Rain", "cold-rain", "Third of three", "null", "[]");
    }

    private static String summary(UUID id, String title, String slug, String description, String releaseYear,
            String genres) {
        return """
                {
                  "id": "%s", "title": "%s", "slug": "%s", "description": "%s", "releaseYear": %s,
                  "posterUrl": null, "genres": %s, "averageRating": null, "status": "PUBLISHED", "videoStatus": null
                }
                """.formatted(id, title, slug, description, releaseYear, genres);
    }

    private static String page(int page, int size, long totalElements, int totalPages, String... items) {
        return """
                {"items": [%s], "page": {"page": %d, "size": %d, "totalElements": %d, "totalPages": %d}}
                """.formatted(String.join(", ", items), page, size, totalElements, totalPages);
    }

    private static String[] ids(UUID... ids) {
        return Stream.of(ids).map(UUID::toString).toArray(String[]::new);
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    // ---- helpers ----

    private ResultActions perform(String uri, String authorization) throws Exception {
        return mockMvc.perform(get(uri).header(HttpHeaders.AUTHORIZATION, authorization));
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
                .claim("email", "movie-api-test@example.com")
                .claim("roles", List.of(role))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims
        )).getTokenValue();
        return "Bearer " + token;
    }
}
