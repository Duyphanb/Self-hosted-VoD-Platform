package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vodplatform.catalog.persistence.Movie;
import com.vodplatform.catalog.persistence.MovieRepository;
import com.vodplatform.catalog.persistence.MovieStatus;
import com.vodplatform.common.support.MovieVisibilityRule;
import com.vodplatform.common.support.BearerSessionFixture;
import com.vodplatform.common.support.PostgresTestDatabase;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** Real JWT, visibility, current-asset query and progress mapping; every response checks the contract. */
@SpringBootTest(properties = {
        "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
        "auth.tokens.access-token-ttl=15m", "auth.tokens.refresh-token-ttl=7d"
})
@AutoConfigureMockMvc
@Transactional
class PlaybackApiIntegrationTests implements PostgresTestDatabase {

    private static final String PATH = "/api/v1/movies/{movieId}/playback";
    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired private MockMvc mockMvc;
    @RegisterExtension
    final BearerSessionFixture bearerSessions = new BearerSessionFixture();
    @Autowired private MovieRepository movies;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void requiresValidJwt() throws Exception {
        UUID movie = movie();
        mockMvc.perform(get(PATH, movie)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        request(movie, "Bearer invalid-token").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        String valid = bearer(user(), "ROLE_USER");
        String tampered = valid.substring(0, valid.lastIndexOf('.') + 1) + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        request(movie, tampered).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_ADMIN"})
    void visibilityIsFirstWithReadyAssetsAndProgressAndWithoutAssets(String role) throws Exception {
        UUID caller = user();
        String token = bearer(caller, role);
        var fixtures = MovieVisibilityRule.persistFixtures(movies);
        UUID ready = asset(fixtures.published(), "READY", 100, T0);
        for (UUID hidden : fixtures.hidden()) {
            asset(hidden, "READY", 100, T0);
            progress(caller, hidden, 90, 100, true);
        }
        entityManager.clear();
        expectPlayback(fixtures.published(), ready, 100, 0, token);
        MovieVisibilityRule.assertHiddenLikeMissing(fixtures, movie -> request(movie, token));

        var withoutAssets = MovieVisibilityRule.persistFixtures(movies);
        UUID publishedAsset = asset(withoutAssets.published(), "READY", 100, T0);
        expectPlayback(withoutAssets.published(), publishedAsset, 100, 0, token);
        MovieVisibilityRule.assertHiddenLikeMissing(withoutAssets, movie -> request(movie, token));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1"})
    void rejectsMalformedAndNonCanonicalMovieIds(String movieId) throws Exception {
        mockMvc.perform(get(PATH, movieId).header(HttpHeaders.AUTHORIZATION, bearer(user(), "ROLE_USER")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPLOADED", "QUEUED", "PROCESSING", "FAILED"})
    void pendingOrFailedReplacementKeepsOldReadyAndNewReadySwitches(String replacement) throws Exception {
        UUID movie = movie();
        UUID old = asset(movie, "READY", 100, T0);
        asset(movie, replacement, 222, T0.plusSeconds(1));
        String token = bearer(user(), "ROLE_USER");
        expectPlayback(movie, old, 100, 0, token);
        UUID next = asset(movie, "READY", 300, T0.plusSeconds(2));
        expectPlayback(movie, next, 300, 0, token);
    }

    @Test
    void tiesUsePostgresUuidOrdering() throws Exception {
        UUID movie = movie();
        UUID signedHigh = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        UUID unsignedHigh = UUID.fromString("80000000-0000-0000-0000-000000000000");
        assertThat(signedHigh.compareTo(unsignedHigh)).isPositive();
        asset(signedHigh, movie, "READY", 100, T0);
        asset(unsignedHigh, movie, "READY", 200, T0);
        expectPlayback(movie, unsignedHigh, 200, 0, bearer(user(), "ROLE_USER"));
    }

    @ParameterizedTest
    @CsvSource({"0, false, 0", "40, false, 40", "90, true, 90", "100, true, 0", "100, false, 0"})
    void resumeUsesCallerRowAndAssetDuration(int position, boolean finished, int resume) throws Exception {
        UUID caller = user();
        UUID movie = movie();
        UUID ready = asset(movie, "READY", 100, T0);
        progress(caller, movie, position, 999, finished);
        expectPlayback(movie, ready, 100, resume, bearer(caller, "ROLE_USER"));
    }

    @Test
    void shorterReplacementResetsResumeWithoutChangingStoredProgress() throws Exception {
        UUID caller = user();
        UUID movie = movie();
        UUID longer = asset(movie, "READY", 200, T0);
        progress(caller, movie, 140, 999, true);
        String token = bearer(caller, "ROLE_USER");
        expectPlayback(movie, longer, 200, 140, token);
        UUID shorter = asset(movie, "READY", 100, T0.plusSeconds(1));
        expectPlayback(movie, shorter, 100, 0, token);
        assertThat(jdbc.queryForMap("select current_seconds,duration_seconds,finished from playback_progress "
                + "where user_id = ? and movie_id = ?", caller, movie))
                .containsEntry("current_seconds", 140).containsEntry("duration_seconds", 999)
                .containsEntry("finished", true);
    }

    @ParameterizedTest
    @CsvSource({"false, 40", "true, 0"})
    void unknownAssetDurationIsExplicitNullAndUsesFinishedOnlyForResume(boolean finished, int resume) throws Exception {
        UUID caller = user();
        UUID movie = movie();
        UUID ready = asset(movie, "READY", null, T0);
        progress(caller, movie, 40, 999, finished);
        expectPlayback(movie, ready, null, resume, bearer(caller, "ROLE_USER"));
    }

    @Test
    void ignoresOtherUsersAndOtherMoviesProgress() throws Exception {
        UUID caller = user();
        UUID movie = movie();
        UUID ready = asset(movie, "READY", 100, T0);
        progress(user(), movie, 50, 100, false);
        progress(caller, movie(), 60, 100, false);
        expectPlayback(movie, ready, 100, 0, bearer(caller, "ROLE_USER"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_ADMIN"})
    void visibleMovieWithoutAnyReadyAssetIsConflict(String role) throws Exception {
        String token = bearer(user(), role);
        UUID movie = movie();
        expectNotReady(movie, token);
        for (String state : List.of("UPLOADED", "QUEUED", "PROCESSING", "FAILED")) {
            UUID asset = asset(movie, state, null, T0);
            expectNotReady(movie, token);
            jdbc.update("delete from video_assets where id = ?", asset);
        }
        asset(movie, "QUEUED", null, T0);
        asset(movie, "FAILED", null, T0.plusSeconds(1));
        expectNotReady(movie, token);
    }

    private void expectNotReady(UUID movie, String token) throws Exception {
        request(movie, token).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VIDEO_NOT_READY"));
    }

    private void expectPlayback(UUID movie, UUID asset, Integer duration, int resume, String token) throws Exception {
        request(movie, token).andExpect(status().isOk()).andExpect(content().json("""
                {"movieId":"%s","videoAssetId":"%s","status":"READY",
                 "hlsManifestUrl":"/hls/video-assets/%s/master.m3u8",
                 "durationSeconds":%s,"resumeSeconds":%d}
                """.formatted(movie, asset, asset, duration, resume), JsonCompareMode.STRICT));
    }

    private ResultActions request(UUID movie, String token) throws Exception {
        return mockMvc.perform(get(PATH, movie).header(HttpHeaders.AUTHORIZATION, token));
    }

    private UUID movie() {
        UUID id = UUID.randomUUID();
        movies.saveAndFlush(new Movie(id, "Playback fixture", "playback-" + id, "Test movie", 2026,
                null, MovieStatus.PUBLISHED, T0));
        return id;
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id,email,password_hash,display_name,status,created_at,updated_at) "
                + "values (?,?,'test-only-unused-hash','Playback fixture','ACTIVE',?,?)",
                id, id + "@example.test", Timestamp.from(T0), Timestamp.from(T0));
        return id;
    }

    private UUID asset(UUID movie, String status, Integer duration, Instant created) {
        UUID id = UUID.randomUUID();
        asset(id, movie, status, duration, created);
        return id;
    }

    private void asset(UUID id, UUID movie, String status, Integer duration, Instant created) {
        jdbc.update("insert into video_assets (id,movie_id,raw_bucket,raw_object_key,hls_bucket,"
                        + "hls_master_object_key,status,duration_seconds,created_at,updated_at) "
                        + "values (?,?,'private-raw',?,'private-hls',?,?,?,?,?)",
                id, movie, "private/raw/" + id, "unrelated/storage/prefix/" + id + "/different.m3u8",
                status, duration, Timestamp.from(created), Timestamp.from(created));
    }

    private void progress(UUID user, UUID movie, int position, Integer duration, boolean finished) {
        jdbc.update("insert into playback_progress (user_id,movie_id,current_seconds,duration_seconds,finished,"
                        + "last_played_at,created_at,updated_at) values (?,?,?,?,?,?,?,?)",
                user, movie, position, duration, finished, Timestamp.from(T0), Timestamp.from(T0), Timestamp.from(T0));
    }

    private String bearer(UUID user, String role) {
        return "Bearer " + bearerSessions.tokenFor(user, user + "@example.test", role);
    }
}
