package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.vodplatform.common.support.BearerSessionFixture;
import com.vodplatform.common.support.PostgresTestDatabase;
import com.vodplatform.common.support.EpisodeFixtures;
import com.vodplatform.progress.service.ProgressNormalizer;
import com.vodplatform.progress.service.StoredProgress;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL selection, full JWT/HTTP wiring and automatic API contract validation. */
@SpringBootTest(properties = {"auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
        "auth.tokens.access-token-ttl=15m", "auth.tokens.refresh-token-ttl=7d"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ContinueWatchingApiIntegrationTests implements PostgresTestDatabase {
    private static final String PATH = "/api/v1/me/continue-watching";
    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00.123456Z");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @RegisterExtension
    final BearerSessionFixture bearerSessions = new BearerSessionFixture();
    @Autowired ObjectMapper mapper;
    @Autowired EntityManagerFactory entityManagerFactory;
    private final List<UUID> movies = new ArrayList<>();
    private final List<UUID> users = new ArrayList<>();
    private final List<UUID> genres = new ArrayList<>();
    private UUID user;
    private String token;

    @BeforeEach
    void fixtures() {
        user = user();
        token = token(user, "ROLE_USER");
    }

    @AfterEach
    void cleanup() {
        for (UUID id : movies) EpisodeFixtures.removeTitle(jdbc, id);
        for (UUID id : genres) jdbc.update("delete from genres where id=?", id);
        for (UUID id : users) jdbc.update("delete from users where id=?", id);
    }

    @Test
    void requiresJwtAndReturnsAnEmptyArrayIgnoringUnknownParameters() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(content().json("[]", JsonCompareMode.STRICT));
        mvc.perform(get(PATH).param("page", "not-a-number").param("userId", user().toString())
                        .header("Authorization", token))
                .andExpect(status().isOk()).andExpect(content().json("[]", JsonCompareMode.STRICT));
    }

    @Test
    void mixedRowsReturnExactCatalogShapeAndTheSameNormalizedProgressAsGet() throws Exception {
        UUID first = valid(40, 100, T0);
        UUID second = valid(60, 200, T0.minusSeconds(1));
        addGenres(first);
        valid(0, 100, T0.plusSeconds(1));
        UUID finished = valid(90, 100, T0.plusSeconds(1));
        jdbc.update("update playback_progress set finished=true where episode_id=?", EpisodeFixtures.episode(jdbc, finished));
        valid(100, 100, T0.plusSeconds(1));
        UUID shorter = valid(140, 200, T0.plusSeconds(1));
        asset(shorter, UUID.randomUUID(), "READY", 100, T0.plusSeconds(1));
        for (Integer duration : Arrays.asList(null, -1, 0)) valid(40, duration, T0.plusSeconds(1));
        for (String state : List.of("NONE", "QUEUED", "FAILED")) {
            UUID id = movie(UUID.randomUUID(), "PUBLISHED");
            if (!state.equals("NONE")) asset(id, UUID.randomUUID(), state, 100, T0);
            progress(user, id, 40, false, T0.plusSeconds(1));
        }
        // Pending/failed newer replacements do not supersede the existing READY asset.
        asset(first, UUID.randomUUID(), "QUEUED", 10, T0.plusSeconds(2));
        asset(first, UUID.randomUUID(), "FAILED", 10, T0.plusSeconds(3));
        mvc.perform(get(PATH).header("Authorization", token)).andExpect(status().isOk())
                .andExpect(content().json("[" + itemJson(first, 40, 100, T0, "[\"Action\",\"Drama\"]")
                        + "," + itemJson(second, 60, 200, T0.minusSeconds(1), "[]") + "]",
                        JsonCompareMode.STRICT));
        JsonNode result = list(token);
        for (JsonNode item : result) {
            String id = item.path("episode").path("id").asString();
            var response = mvc.perform(get("/api/v1/episodes/" + id + "/progress")
                            .header("Authorization", token)).andExpect(status().isOk()).andReturn();
            assertThat(item.path("progress"))
                    .isEqualTo(mapper.readTree(response.getResponse().getContentAsString()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "ROLE_ADMIN"})
    void catalogVisibilityAppliesToEveryRole(String role) throws Exception {
        UUID published = valid(40, 100, T0);
        for (String state : List.of("DRAFT", "ARCHIVED")) {
            UUID hidden = movie(UUID.randomUUID(), state);
            asset(hidden, UUID.randomUUID(), "READY", 100, T0);
            progress(user, hidden, 40, false, T0.plusSeconds(1));
        }
        assertThat(ids(list(token(user, role)))).containsExactly(published);
    }

    @Test
    void ownershipIncludesIndependentRowsOnTheSameMovie() throws Exception {
        UUID shared = valid(20, 100, T0);
        UUID other = user();
        progress(other, shared, 70, false, T0.plusSeconds(2));
        UUID privateMovie = movie(UUID.randomUUID(), "PUBLISHED");
        asset(privateMovie, UUID.randomUUID(), "READY", 100, T0);
        progress(other, privateMovie, 35, false, T0);
        JsonNode mine = list(token);
        assertThat(ids(mine)).containsExactly(shared);
        assertThat(mine.get(0).path("progress").path("currentSeconds").asInt()).isEqualTo(20);
        JsonNode theirs = list(token(other, "ROLE_USER"));
        assertThat(ids(theirs)).containsExactly(shared, privateMovie);
        assertThat(theirs.get(0).path("progress").path("currentSeconds").asInt()).isEqualTo(70);
    }

    @Test
    void newestFiftyRowsAreReturnedInDescendingTimestampOrder() throws Exception {
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 55; i++) expected.addFirst(valid(40, 100, T0.plusSeconds(i)));
        assertThat(ids(list(token))).containsExactlyElementsOf(expected.subList(0, 50));
    }

    @Test
    void timestampTiesUsePostgresUuidOrderIncludingAcrossTheLimitBoundary() throws Exception {
        UUID low = UUID.fromString("7fffffff-0000-0000-0000-000000000000");
        UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");
        List<UUID> newer = new ArrayList<>();
        for (int i = 0; i < 49; i++) newer.addFirst(valid(40, 100, T0.plusSeconds(i + 1)));
        for (UUID id : List.of(low, high)) {
            movie(id, "PUBLISHED");
            asset(id, UUID.randomUUID(), "READY", 100, T0);
            progress(user, id, 40, false, T0);
        }
        List<UUID> result = ids(list(token));
        assertThat(result).containsExactlyElementsOf(
                java.util.stream.Stream.concat(newer.stream(), java.util.stream.Stream.of(high)).toList());
        // Without the 49 newer rows both tied movies remain in database order, not UUID.compareTo.
        for (UUID id : newer) jdbc.update("delete from playback_progress where episode_id=?", EpisodeFixtures.episode(jdbc, id));
        assertThat(ids(list(token))).containsExactly(high, low);
    }

    @Test
    void invalidNewerRowsNeverConsumeTheLimit() throws Exception {
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 50; i++) expected.addFirst(valid(40, 100, T0.plusSeconds(i)));
        for (int i = 0; i < 10; i++) {
            UUID id = valid(40, 100, T0.plusSeconds(100 + i));
            switch (i % 3) {
                case 0 -> jdbc.update("update titles set status='DRAFT' where id=?", id);
                case 1 -> jdbc.update("update playback_progress set finished=true where episode_id=?", EpisodeFixtures.episode(jdbc, id));
                default -> jdbc.update("update video_assets set status='QUEUED' where episode_id=?", EpisodeFixtures.episode(jdbc, id));
            }
        }
        assertThat(ids(list(token))).containsExactlyElementsOf(expected);
    }

    @Test
    void currentReadyAssetTiesUsePostgresUuidOrderAndItsDuration() throws Exception {
        UUID movie = movie(UUID.randomUUID(), "PUBLISHED");
        asset(movie, UUID.fromString("7fffffff-0000-0000-0000-000000000000"), "READY", 30, T0);
        asset(movie, UUID.fromString("80000000-0000-0000-0000-000000000000"), "READY", 200, T0);
        progress(user, movie, 40, false, T0);
        JsonNode result = list(token);
        assertThat(ids(result)).containsExactly(movie);
        assertThat(result.get(0).path("progress").path("durationSeconds").asInt()).isEqualTo(200);
    }

    @Test
    void sqlMembershipMatchesJavaPredicateAcrossDurationPositionAndCompletionMatrix() throws Exception {
        List<UUID> expected = new ArrayList<>();
        int sequence = 0;
        for (Integer duration : Arrays.asList(null, -1, 0, 1, 100, Integer.MAX_VALUE)) {
            for (int position : new int[]{0, 1, 40, 90, 100, Integer.MAX_VALUE}) {
                for (boolean finished : new boolean[]{false, true}) {
                    Instant played = T0.plusSeconds(sequence++);
                    UUID movie = movie(UUID.randomUUID(), "PUBLISHED");
                    asset(movie, UUID.randomUUID(), "READY", duration, T0);
                    progress(user, movie, position, finished, played);
                    if (ProgressNormalizer.isResumable(new StoredProgress(position, 999, finished, played), duration)) {
                        expected.addFirst(movie);
                    }
                }
            }
        }
        assertThat(expected).isNotEmpty().hasSizeLessThan(50);
        assertThat(ids(list(token))).containsExactlyElementsOf(expected);
    }

    @Test
    void sessionMissAddsOneReadAndTheProjectionUsesFourStatementsForTwoOrFiftyItems() throws Exception {
        for (int index = 0; index < 2; index++) {
            valid(40, 100, T0.plusSeconds(index));
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            assertThat(list(token).size()).isEqualTo(2);
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(5);
            statistics.clear();
            assertThat(list(token).size()).isEqualTo(2);
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
            for (int index = 2; index < 50; index++) {
                valid(40, 100, T0.plusSeconds(index));
            }
            statistics.clear();
            assertThat(list(token).size()).isEqualTo(50);
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
        } finally {
            statistics.setStatisticsEnabled(enabled);
        }
    }

    private JsonNode list(String auth) throws Exception {
        return mapper.readTree(mvc.perform(get(PATH).header("Authorization", auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private List<UUID> ids(JsonNode result) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode item : result) ids.add(UUID.fromString(item.path("title").path("id").asString()));
        return ids;
    }

    private String itemJson(UUID movie, int position, int duration, Instant played, String genres) {
        UUID episode = EpisodeFixtures.episode(jdbc, movie);
        var progress = """
                {"episodeId":"%s","currentSeconds":%d,"durationSeconds":%d,
                 "finished":false,"lastPlayedAt":"%s"}
                """.formatted(episode, position, duration, played);
        return """
                {"kind":"RESUME","title":{"id":"%s","title":"Continue fixture","slug":"continue-%s",
                  "description":"Continue fixture","releaseYear":2026,"posterUrl":null,
                  "genres":%s,"averageRating":null,"status":"PUBLISHED","contentType":"MOVIE",
                  "partLabel":null,"episodeCount":1,"playableEpisodeCount":1,"availability":"PLAYABLE"},
                 "episode":{"id":"%s","titleId":"%s","ordinal":1,"displayLabel":"1","name":null,
                  "videoStatus":"READY","progress":%s},"progress":%s}
                """.formatted(movie, movie, genres, episode, movie, progress, progress);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PUBLISHED"})
    void latestFinishedEpisodeDoesNotFallBackOrSkipItsImmediateSuccessor(String successorStatus) throws Exception {
        UUID title = valid(32, 100, T0);
        jdbc.update("update titles set content_type='TV_SEASON' where id=?", title);
        UUID anchor = extraEpisode(title, 2, "PUBLISHED");
        UUID successor = extraEpisode(title, 3, successorStatus);
        UUID later = extraEpisode(title, 4, "PUBLISHED");
        assertThat(later).isNotEqualTo(successor);
        rawAsset(anchor, UUID.randomUUID(), "READY", 100, T0);
        rawAsset(later, UUID.randomUUID(), "READY", 100, T0);
        rawProgress(user, anchor, 92, true, T0.plusSeconds(10));
        assertThat(list(token)).isEmpty();

        rawAsset(successor, UUID.randomUUID(), "READY", 100, T0);
        if (successorStatus.equals("DRAFT")) {
            assertThat(list(token)).isEmpty();
            jdbc.update("update episodes set status='PUBLISHED' where id=?", successor);
        }
        var result = list(token);
        assertThat(result.size()).isEqualTo(1);
        assertThat(result.get(0).path("kind").asString()).isEqualTo("NEXT_EPISODE");
        assertThat(result.get(0).path("episode").path("id").asString()).isEqualTo(successor.toString());
        assertThat(result.get(0).path("progress").isNull()).isTrue();
    }

    private UUID extraEpisode(UUID title, int ordinal, String status) {
        UUID episode = UUID.randomUUID();
        jdbc.update("""
                insert into episodes (id,title_id,ordinal,display_label,status,created_at,updated_at)
                values (?, ?, ?, ?, ?, now(), now())
                """, episode, title, ordinal, Integer.toString(ordinal), status);
        return episode;
    }

    private UUID valid(int position, Integer duration, Instant played) {
        UUID id = movie(UUID.randomUUID(), "PUBLISHED");
        asset(id, UUID.randomUUID(), "READY", duration, T0);
        progress(user, id, position, false, played);
        return id;
    }

    private UUID movie(UUID id, String status) {
        movies.add(id);
        jdbc.update("""
                insert into titles(id,title,slug,description,release_year,status,created_at,updated_at,content_type)
                values(?,'Continue fixture',?,'Continue fixture',2026,?,?,?,'MOVIE')
                """, id, "continue-" + id, status, Timestamp.from(T0), Timestamp.from(T0));
        EpisodeFixtures.addFilmEpisode(jdbc, id);
        return id;
    }

    private void asset(UUID movie, UUID id, String state, Integer duration, Instant created) {
        rawAsset(EpisodeFixtures.episode(jdbc, movie), id, state, duration, created);
    }

    private void rawAsset(UUID episode, UUID id, String state, Integer duration, Instant created) {
        jdbc.update("""
                insert into video_assets(id,episode_id,raw_bucket,raw_object_key,status,duration_seconds,created_at,updated_at)
                values(?,?,'fixture',?,?,?,?,?)
                """, id, episode, id.toString(), state, duration, Timestamp.from(created), Timestamp.from(created));
    }

    private void progress(UUID owner, UUID movie, int position, boolean finished, Instant played) {
        rawProgress(owner, EpisodeFixtures.episode(jdbc, movie), position, finished, played);
    }

    private void rawProgress(UUID owner, UUID episode, int position, boolean finished, Instant played) {
        jdbc.update("""
                insert into playback_progress(user_id,episode_id,current_seconds,duration_seconds,finished,
                                              last_played_at,created_at,updated_at)
                values(?,?,?,999,?,?,?,?)
                """, owner, episode, position, finished, Timestamp.from(played), Timestamp.from(T0), Timestamp.from(T0));
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        users.add(id);
        jdbc.update("""
                insert into users(id,email,password_hash,display_name,status,created_at,updated_at)
                values(?,?,'unused','Continue fixture','ACTIVE',?,?)
                """, id, id + "@example.test", Timestamp.from(T0), Timestamp.from(T0));
        return id;
    }

    private void addGenres(UUID movie) {
        for (String name : List.of("Drama", "Action")) {
            UUID id = UUID.randomUUID();
            genres.add(id);
            jdbc.update("insert into genres(id,name,slug) values(?,?,?)", id, name, name.toLowerCase() + "-" + id);
            jdbc.update("insert into title_genres(title_id,genre_id) values(?,?)", movie, id);
        }
    }

    private String token(UUID id, String role) {
        return "Bearer " + bearerSessions.tokenFor(id, "continue@example.test", role);
    }
}
