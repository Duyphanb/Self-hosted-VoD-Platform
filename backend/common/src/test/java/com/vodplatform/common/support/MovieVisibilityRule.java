package com.vodplatform.common.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import com.vodplatform.catalog.persistence.Movie;
import com.vodplatform.catalog.persistence.MovieRepository;
import com.vodplatform.catalog.persistence.MovieStatus;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.skyscreamer.jsonassert.Customization;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.comparator.CustomComparator;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.json.JsonAssert;
import org.springframework.test.json.JsonComparator;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Fixtures and assertions for the movie visibility rule (ADR-009,
 * {@code SECURITY.md#movie-visibility}): an endpoint that takes a {@code movieId} answers a
 * {@code DRAFT} or {@code ARCHIVED} movie exactly as it answers a missing one, with 404
 * {@code MOVIE_NOT_FOUND}, so the response never reveals that the movie exists. ADR-009 requires
 * these 404 tests for movie detail and, in Sprints 5-8, for playback, HLS, progress, rating,
 * watchlist, history and search; each of them reuses this class instead of writing its own
 * fixtures.
 *
 * <p>Typical use in a full-context {@code @Transactional} test:
 *
 * <pre>{@code
 * MovieVisibilityRule.Fixtures movies = MovieVisibilityRule.persistFixtures(movieRepository);
 * mockMvc.perform(get(PLAYBACK, movies.published()).header(AUTHORIZATION, user)).andExpect(status().isOk());
 * MovieVisibilityRule.assertHiddenLikeMissing(movies,
 *         movieId -> mockMvc.perform(get(PLAYBACK, movieId).header(AUTHORIZATION, user)));
 * }</pre>
 *
 * <p>Check the endpoint with {@link Fixtures#published()} as well, so the 404s cannot come from an
 * endpoint that fails for every movie. Admin callers follow the same rule everywhere except the
 * admin exceptions in {@code SECURITY.md}, so run the assertion with an admin token too where the
 * rule applies to admins.
 */
public final class MovieVisibilityRule {

    /** Creation time of the fixture movies. */
    public static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static final JsonComparator STRICT_WITH_ANY_TIMESTAMP = JsonAssert.comparator(new CustomComparator(
            JSONCompareMode.STRICT,
            new Customization("timestamp", (actual, expected) -> isInstant(actual))
    ));
    private static final String MOVIE_NOT_FOUND = """
            {"timestamp": "any instant", "status": 404, "code": "MOVIE_NOT_FOUND", "message": "Movie not found"}
            """;

    private MovieVisibilityRule() {
    }

    /**
     * The movies the rule is checked with.
     *
     * @param published a {@code PUBLISHED} movie, which every caller may see
     * @param draft     a {@code DRAFT} movie
     * @param archived  an {@code ARCHIVED} movie
     * @param missing   an id that no movie has
     */
    public record Fixtures(UUID published, UUID draft, UUID archived, UUID missing) {

        /** The movies that exist but must look missing: the draft and the archived one. */
        public List<UUID> hidden() {
            return List.of(draft, archived);
        }
    }

    /**
     * A request to the endpoint under test for one movie id, with the caller's credentials.
     */
    @FunctionalInterface
    public interface MovieRequest {

        ResultActions perform(UUID movieId) throws Exception;
    }

    /**
     * Saves one {@code PUBLISHED}, one {@code DRAFT} and one {@code ARCHIVED} movie with random ids
     * and picks an id no movie has. The movies are written in the caller's transaction, so a
     * {@code @Transactional} test rolls them back; a test without a transaction must delete them.
     */
    public static Fixtures persistFixtures(MovieRepository movies) {
        UUID published = save(movies, MovieStatus.PUBLISHED);
        UUID draft = save(movies, MovieStatus.DRAFT);
        UUID archived = save(movies, MovieStatus.ARCHIVED);
        movies.flush();
        UUID missing = UUID.randomUUID();
        assertThat(movies.existsById(missing)).as("random id of the missing movie").isFalse();
        return new Fixtures(published, draft, archived, missing);
    }

    /**
     * Asserts that the endpoint answers the missing movie with 404 {@code MOVIE_NOT_FOUND}, and
     * each hidden movie with the same status, headers and body (the error timestamp aside).
     */
    public static void assertHiddenLikeMissing(Fixtures fixtures, MovieRequest request) throws Exception {
        MvcResult missing = request.perform(fixtures.missing()).andReturn();
        assertMovieNotFound(missing, "missing movie " + fixtures.missing());
        for (UUID hidden : fixtures.hidden()) {
            MvcResult result = request.perform(hidden).andReturn();
            String description = "hidden movie " + hidden;
            assertMovieNotFound(result, description);
            assertThat(headers(result.getResponse()))
                    .as("headers for the %s, compared with the missing movie", description)
                    .isEqualTo(headers(missing.getResponse()));
            content().json(missing.getResponse().getContentAsString(), STRICT_WITH_ANY_TIMESTAMP).match(result);
        }
    }

    private static UUID save(MovieRepository movies, MovieStatus status) {
        UUID id = UUID.randomUUID();
        String kind = status.name().toLowerCase(Locale.ROOT);
        movies.save(new Movie(
                id,
                "Visibility fixture (" + kind + ")",
                "visibility-" + kind + "-" + id,
                "Movie visibility rule fixture",
                2026,
                null,
                status,
                CREATED_AT
        ));
        return id;
    }

    private static void assertMovieNotFound(MvcResult result, String description) throws Exception {
        MockHttpServletResponse response = result.getResponse();
        assertThat(response.getStatus()).as("status for the %s", description).isEqualTo(404);
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.APPLICATION_JSON))
                .as("content type for the %s", description)
                .isTrue();
        content().json(MOVIE_NOT_FOUND, STRICT_WITH_ANY_TIMESTAMP).match(result);
    }

    /** Header names and values; the names of a mock response are case-preserving and ordered. */
    private static List<String> headers(MockHttpServletResponse response) {
        List<String> headers = new ArrayList<>();
        for (String name : response.getHeaderNames()) {
            headers.add(name + ": " + response.getHeaders(name));
        }
        Collections.sort(headers);
        return headers;
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
}
