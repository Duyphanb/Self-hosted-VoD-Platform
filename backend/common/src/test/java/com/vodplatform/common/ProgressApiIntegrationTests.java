package com.vodplatform.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.vodplatform.catalog.persistence.MovieRepository;
import com.vodplatform.common.support.MovieVisibilityRule;
import com.vodplatform.common.support.BearerSessionFixture;
import com.vodplatform.common.support.PostgresTestDatabase;
import com.vodplatform.common.support.EpisodeFixtures;
import com.vodplatform.progress.dto.ProgressRequest;
import com.vodplatform.progress.persistence.WatchHistoryWriter;
import com.vodplatform.progress.service.ProgressService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.ObjectMapper;

/** Committed isolated fixtures, real PostgreSQL upserts and separate HTTP request transactions. */
@SpringBootTest(properties={"auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
        "auth.tokens.access-token-ttl=15m", "auth.tokens.refresh-token-ttl=7d"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class ProgressApiIntegrationTests implements PostgresTestDatabase {
    private static final Instant T0=Instant.parse("2026-11-01T00:00:00.123456789Z");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @RegisterExtension
    final BearerSessionFixture bearerSessions = new BearerSessionFixture();
    @Autowired ObjectMapper mapper;
    @Autowired MovieRepository movies;
    @Autowired ProgressService service;
    @MockitoBean Clock clock;
    @MockitoSpyBean WatchHistoryWriter history;
    private final AtomicReference<Instant> now=new AtomicReference<>(T0);
    private final ThreadLocal<Instant> threadTime=new ThreadLocal<>();
    private final List<UUID> movieIds=new ArrayList<>(), userIds=new ArrayList<>();
    private UUID user, movie, asset;
    private String token;

    @BeforeEach void fixtures() {
        when(clock.instant()).thenAnswer(call -> threadTime.get()==null ? now.get() : threadTime.get());
        user=user(); movie=movie(); asset=asset(movie,"READY",100,0); token=token(user,"ROLE_USER");
    }
    @AfterEach void cleanup() {
        for (UUID id : movieIds) {
            EpisodeFixtures.removeTitle(jdbc, id);
        }
        for(UUID id:userIds) jdbc.update("delete from users where id=?",id);
    }

    @Test void exactEmptyAndSavedResponsesUseAssetDurationAndMicroseconds() throws Exception {
        mvc.perform(get(path(movie)).header("Authorization",token)).andExpect(status().isOk())
            .andExpect(content().json("""
                {"episodeId":"%s","currentSeconds":0,"durationSeconds":null,"finished":false,"lastPlayedAt":null}
                """.formatted(movie),JsonCompareMode.STRICT));
        var saved=save(movie,asset,40,false,token);
        saved.andExpect(status().isOk()).andExpect(content().json("""
                {"episodeId":"%s","currentSeconds":40,"durationSeconds":100,"finished":false,
                 "lastPlayedAt":"2026-11-01T00:00:00.123456Z"}
                """.formatted(movie),JsonCompareMode.STRICT));
        assertThat(progress(user,movie).get("duration_seconds")).isEqualTo(100);
        assertThat(time(progress(user,movie),"last_played_at")).isEqualTo(T0.truncatedTo(ChronoUnit.MICROS));
        assertThat(jdbc.queryForObject("select count(*) from watch_history where user_id=? and episode_id=?",Integer.class,user,movie)).isEqualTo(1);
    }

    @Test void validationAndAuthenticationPrecedeWrites() throws Exception {
        for(String body:List.of("{}", "{\"videoAssetId\":\""+asset+"\",\"finished\":false}",
            "{\"videoAssetId\":\""+asset+"\",\"currentSeconds\":1}",
            "{\"currentSeconds\":1,\"finished\":false}",
            "{\"videoAssetId\":\""+asset+"\",\"currentSeconds\":-1,\"finished\":false}")) {
            mvc.perform(put(path(movie)).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        mvc.perform(put(path(movie)).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"videoAssetId\":\"1-1-1-1-1\",\"currentSeconds\":1,\"finished\":false}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        for(String id:List.of("not-a-uuid","1-1-1-1-1")) {
            mvc.perform(get(path(id)).header("Authorization",token)).andExpect(status().isBadRequest());
            mvc.perform(put(path(id)).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
                .content(body(asset,1,false))).andExpect(status().isBadRequest());
        }
        for(String auth:List.of("", "Bearer invalid")) {
            mvc.perform(get(path(movie)).header("Authorization",auth)).andExpect(status().isUnauthorized());
            mvc.perform(put(path(movie)).header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
                .content(body(asset,1,false))).andExpect(status().isUnauthorized());
        }
        assertThat(progressCount()).isZero(); assertThat(historyCount()).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"ROLE_USER","ROLE_ADMIN"})
    void visibilityIsSharedForReadsAndWrites(String role) throws Exception {
        var f=MovieVisibilityRule.persistFixtures(movies);
        movieIds.addAll(List.of(f.published(),f.draft(),f.archived()));
        UUID visibleAsset=asset(f.published(),"READY",100,0);
        for(UUID hidden:f.hidden()) {
            asset(hidden,"READY",100,0);
            jdbc.update("insert into playback_progress(user_id,episode_id,current_seconds,finished,last_played_at,created_at,updated_at) values(?,?,20,false,?,?,?)",
                user,EpisodeFixtures.episode(jdbc, hidden),Timestamp.from(T0),Timestamp.from(T0),Timestamp.from(T0));
        }
        String auth=token(user,role);
        var before=jdbc.queryForList("select * from playback_progress where user_id=? order by episode_id",user);
        MovieVisibilityRule.assertHiddenEpisodeLikeMissing(f,id -> mvc.perform(get(path(id)).header("Authorization",auth)));
        MovieVisibilityRule.assertHiddenEpisodeLikeMissing(f,id -> save(id,visibleAsset,30,false,auth));
        assertThat(jdbc.queryForList("select * from playback_progress where user_id=? order by episode_id",user)).isEqualTo(before);
        assertThat(historyCount()).isZero();
        mvc.perform(get(path(f.published())).header("Authorization",auth)).andExpect(status().isOk());
        save(f.published(),visibleAsset,30,false,auth).andExpect(status().isOk());
    }

    @Test void nonCurrentAndNotReadyNeverWriteEitherTable() throws Exception {
        save(movie,asset,20,false,token).andExpect(status().isOk());
        var before=progress(user,movie); var historyBefore=history(user,movie);
        asset(movie,"READY",200,1);
        for(UUID wrong:List.of(asset,UUID.randomUUID())) save(movie,wrong,90,true,token)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VIDEO_ASSET_NOT_CURRENT"));
        assertThat(progress(user,movie)).isEqualTo(before); assertThat(history(user,movie)).isEqualTo(historyBefore);
        UUID empty=movie();
        for(String state:List.of("NONE","QUEUED","FAILED")) {
            if(!state.equals("NONE")) asset(empty,state,null,0);
            save(empty,UUID.randomUUID(),1,false,token).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VIDEO_NOT_READY"));
            mvc.perform(get(path(empty)).header("Authorization",token)).andExpect(status().isOk());
        }
        assertThat(progressCount()).isEqualTo(1); assertThat(historyCount()).isEqualTo(1);
    }

    @Test void completionHistoryAndOlderWriteAreForwardOnly() throws Exception {
        save(movie,asset,89,false,token).andExpect(jsonPath("$.finished").value(false));
        var first=history(user,movie); assertThat(first.get("completed_at")).isNull();
        now.set(T0.plusSeconds(1)); save(movie,asset,90,false,token).andExpect(jsonPath("$.finished").value(true));
        var completed=history(user,movie).get("completed_at");
        now.set(T0.plusSeconds(2)); save(movie,asset,20,false,token).andExpect(jsonPath("$.finished").value(false));
        now.set(T0.plusSeconds(3)); save(movie,asset,1,true,token).andExpect(jsonPath("$.currentSeconds").value(100));
        assertThat(history(user,movie).get("completed_at")).isEqualTo(completed);
        assertThat(history(user,movie).get("started_at")).isEqualTo(first.get("started_at"));
        assertThat(time(history(user,movie),"last_watched_at"))
            .isEqualTo(T0.plusSeconds(3).truncatedTo(ChronoUnit.MICROS));
        var p=progress(user,movie); var h=history(user,movie);
        now.set(T0); save(movie,asset,2,false,token).andExpect(jsonPath("$.currentSeconds").value(100));
        assertThat(progress(user,movie)).isEqualTo(p); assertThat(history(user,movie)).isEqualTo(h);
        now.set(T0.plusSeconds(4)); save(movie,asset,999,false,token).andExpect(jsonPath("$.currentSeconds").value(100));
    }

    @Test void unknownDurationsAndShorterReplacementNormalizeEveryResponse() throws Exception {
        for(Integer d:Arrays.asList(null,-1)) {
            jdbc.update("update video_assets set duration_seconds=? where id=?",d,asset);
            save(movie,asset,500,false,token).andExpect(jsonPath("$.durationSeconds").isEmpty())
                .andExpect(jsonPath("$.currentSeconds").value(500)).andExpect(jsonPath("$.finished").value(false));
            now.set(now.get().plusSeconds(1));
            save(movie,asset,500,true,token).andExpect(jsonPath("$.currentSeconds").value(500));
        }
        asset=asset(movie,"READY",50,1);
        mvc.perform(get(path(movie)).header("Authorization",token)).andExpect(jsonPath("$.currentSeconds").value(50))
            .andExpect(jsonPath("$.durationSeconds").value(50));
        var putResult=save(movie,asset,999,false,token).andReturn();
        var getResult=mvc.perform(get(path(movie)).header("Authorization",token)).andReturn();
        assertThat(mapper.readTree(putResult.getResponse().getContentAsString())).isEqualTo(mapper.readTree(getResult.getResponse().getContentAsString()));
        jdbc.update("delete from video_assets where episode_id=?",movie);
        mvc.perform(get(path(movie)).header("Authorization",token)).andExpect(jsonPath("$.currentSeconds").value(50));
    }

    @Test void ownershipIsPerUserAndHistoryFailureRollsBackProgress() throws Exception {
        UUID other=user(); String auth=token(other,"ROLE_USER");
        save(movie,asset,20,false,token).andExpect(status().isOk());
        save(movie,asset,60,false,auth).andExpect(status().isOk());
        mvc.perform(get(path(movie)).header("Authorization",token)).andExpect(jsonPath("$.currentSeconds").value(20));
        mvc.perform(get(path(movie)).header("Authorization",auth)).andExpect(jsonPath("$.currentSeconds").value(60));
        var p=progress(user,movie); var h=history(user,movie); var otherP=progress(other,movie); var otherH=history(other,movie);
        doThrow(new DataIntegrityViolationException("injected history failure")).when(history).record(any(),any(),anyBoolean(),any());
        now.set(T0.plusSeconds(10));
        assertThatThrownBy(() -> service.save(user,movie,new ProgressRequest(asset,99,null,false)))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(progress(user,movie)).isEqualTo(p); assertThat(history(user,movie)).isEqualTo(h);
        assertThat(progress(other,movie)).isEqualTo(otherP); assertThat(history(other,movie)).isEqualTo(otherH);
    }

    @Test
    void clientDurationIsNullableIgnoredAndValidatedWithoutWrites() throws Exception {
        for (String duration : List.of("", ",\"durationSeconds\":null", ",\"durationSeconds\":999")) {
            mvc.perform(put(path(movie)).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"videoAssetId\":\"" + asset + "\",\"currentSeconds\":40,\"finished\":false" + duration + "}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.durationSeconds").value(100));
        }
        var progressBefore = progress(user,movie);
        var historyBefore = history(user,movie);
        now.set(T0.plusSeconds(10));
        mvc.perform(put(path(movie)).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
                .content(body(asset,95,true).replace("\"durationSeconds\":999", "\"durationSeconds\":-1")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(progress(user,movie)).isEqualTo(progressBefore);
        assertThat(history(user,movie)).isEqualTo(historyBefore);
        assertThat(progressCount()).isEqualTo(1);
        assertThat(historyCount()).isEqualTo(1);
    }

    @ParameterizedTest(name = "existing={0}, holderLater={1}")
    @CsvSource({"false,true", "false,false", "true,true", "true,false"})
    void contendingHttpWritesSerializeAndRejectOlderWaiter(boolean existing, boolean holderLater) throws Exception {
        if (existing) save(movie,asset,20,false,token).andExpect(status().isOk());
        Object originalHistoryId = existing ? history(user,movie).get("id") : null;
        Instant holderTime = T0.plusSeconds(holderLater ? 2 : 1).truncatedTo(ChronoUnit.MICROS);
        Instant waiterTime = T0.plusSeconds(holderLater ? 1 : 2).truncatedTo(ChronoUnit.MICROS);
        var holderEntered = new CountDownLatch(1);
        var releaseHolder = new CountDownLatch(1);
        var holderThread = new AtomicReference<Thread>();
        var holderPid = new AtomicInteger();
        doAnswer(call -> {
            if (Thread.currentThread() == holderThread.get()) {
                holderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                holderEntered.countDown();
                if (!releaseHolder.await(20,TimeUnit.SECONDS)) throw new IllegalStateException("Holder release timed out");
            }
            return call.callRealMethod();
        }).when(history).record(any(),any(),anyBoolean(),any());

        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> holder = pool.submit(() -> {
                holderThread.set(Thread.currentThread());
                return timedSave(holderTime,95);
            });
            assertThat(holderEntered.await(10,TimeUnit.SECONDS)).as("holder reached history inside its open transaction").isTrue();
            Future<MvcResult> waiter = pool.submit(() -> timedSave(waiterTime,40));
            awaitProgressLockWait(holderPid.get());
            releaseHolder.countDown();

            var holderBody = mapper.readTree(holder.get(20,TimeUnit.SECONDS).getResponse().getContentAsString());
            var waiterBody = mapper.readTree(waiter.get(20,TimeUnit.SECONDS).getResponse().getContentAsString());
            assertThat(holderBody).isEqualTo(mapper.readTree(progressJson(95,true,holderTime)));
            int finalPosition = holderLater ? 95 : 40;
            Instant finalTime = T0.plusSeconds(2).truncatedTo(ChronoUnit.MICROS);
            assertThat(waiterBody).isEqualTo(mapper.readTree(progressJson(finalPosition,holderLater,finalTime)));

            Instant created = existing ? T0.truncatedTo(ChronoUnit.MICROS) : holderTime;
            assertThat(progress(user,movie)).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "user_id",user, "episode_id",movie, "current_seconds",finalPosition, "duration_seconds",100,
                    "finished",holderLater, "last_played_at",Timestamp.from(finalTime),
                    "created_at",Timestamp.from(created), "updated_at",Timestamp.from(finalTime)));
            var historyRow = history(user,movie);
            assertThat(historyRow.get("id")).isInstanceOf(UUID.class);
            if (existing) assertThat(historyRow.get("id")).isEqualTo(originalHistoryId);
            assertThat(historyRow).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "id",historyRow.get("id"), "user_id",user, "episode_id",movie,
                    "started_at",Timestamp.from(created), "last_watched_at",Timestamp.from(finalTime),
                    "completed_at",Timestamp.from(holderTime)));
            verify(history,times((existing ? 1 : 0) + (holderLater ? 1 : 2))).record(any(),any(),anyBoolean(),any());
            if (holderLater) verify(history,never()).record(user,movie,false,waiterTime);
            assertThat(progressCount()).isEqualTo(1);
            assertThat(historyCount()).isEqualTo(1);
        } finally {
            releaseHolder.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).as("request threads terminated").isTrue();
        }
    }

    private MvcResult timedSave(Instant time, int position) throws Exception {
        threadTime.set(time);
        try {
            return save(movie,asset,position,false,token).andExpect(status().isOk()).andReturn();
        } finally {
            threadTime.remove();
        }
    }

    /** The observer is a third connection; match the exact blocking holder, not an unrelated lock. */
    private void awaitProgressLockWait(int holderPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            Boolean waiting = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
                try (var statement = connection.prepareStatement("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                          WHERE datname=current_database() AND pid<>pg_backend_pid()
                            AND wait_event_type='Lock' AND ?=ANY(pg_blocking_pids(pid))
                            AND query LIKE '%INSERT INTO playback_progress%')
                        """)) {
                    statement.setQueryTimeout(2);
                    statement.setInt(1,holderPid);
                    try (var result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
                }
            });
            if (Boolean.TRUE.equals(waiting)) return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("Waiter did not block on holder's PostgreSQL progress lock within 10 seconds");
    }

    private String progressJson(int position, boolean finished, Instant time) {
        return """
                {"episodeId":"%s","currentSeconds":%d,"durationSeconds":100,"finished":%s,"lastPlayedAt":"%s"}
                """.formatted(movie,position,finished,time);
    }

    private ResultActions save(UUID id,UUID video,int position,boolean finished,String auth) throws Exception {
        return mvc.perform(put(path(id)).header("Authorization",auth).contentType(MediaType.APPLICATION_JSON).content(body(video,position,finished)));
    }
    private String body(UUID id,int position,boolean finished) {return "{\"videoAssetId\":\""+id+"\",\"currentSeconds\":"+position+",\"durationSeconds\":999,\"finished\":"+finished+"}";}
    private String path(Object id) {
        Object episode = id instanceof UUID uuid ? EpisodeFixtures.episode(jdbc, uuid) : id;
        return "/api/v1/episodes/" + episode + "/progress";
    }
    private Map<String, Object> progress(UUID user, UUID episode) {
        return jdbc.queryForMap("select * from playback_progress where user_id=? and episode_id=?", user, episode);
    }

    private Map<String, Object> history(UUID user, UUID episode) {
        return jdbc.queryForMap("select * from watch_history where user_id=? and episode_id=?", user, episode);
    }

    private Instant time(Map<String,Object> row,String col) {return ((Timestamp)row.get(col)).toInstant();}
    private int progressCount() {return jdbc.queryForObject("select count(*) from playback_progress where user_id=?",Integer.class,user);}
    private int historyCount() {return jdbc.queryForObject("select count(*) from watch_history where user_id=?",Integer.class,user);}
    private UUID user() {UUID id=UUID.randomUUID();userIds.add(id);jdbc.update("insert into users(id,email,password_hash,display_name,status,created_at,updated_at) values(?,?,'unused','Progress fixture','ACTIVE',?,?)",id,id+"@example.test",Timestamp.from(T0),Timestamp.from(T0));return id;}
    private UUID movie() {
        UUID id = UUID.randomUUID();
        movieIds.add(id);
        jdbc.update("""
                insert into titles (id, title, slug, description, status, created_at, updated_at, content_type)
                values (?, 'Progress fixture', ?, 'Progress fixture', 'PUBLISHED', ?, ?, 'MOVIE')
                """, id, "progress-" + id, Timestamp.from(T0), Timestamp.from(T0));
        return EpisodeFixtures.addFilmEpisode(jdbc, id);
    }
    /**
     * Inserts an asset of an episode. {@code movie()} returns episode IDs; the visibility fixtures pass title IDs,
     * which resolve to the title's film episode.
     */
    private UUID asset(UUID episodeOrTitle, String state, Integer duration, int second) {
        UUID id = UUID.randomUUID();
        UUID episode = EpisodeFixtures.episode(jdbc, episodeOrTitle);
        jdbc.update("""
                insert into video_assets(
                    id,episode_id,raw_bucket,raw_object_key,status,duration_seconds,created_at,updated_at)
                values(?,?,'fixture',?,?,?,?,?)
                """, id, episode, id.toString(), state, duration, Timestamp.from(T0.plusSeconds(second)),
                Timestamp.from(T0));
        return id;
    }

    private String token(UUID user, String role) {
        return "Bearer " + bearerSessions.tokenFor(user, "progress@example.test", role);
    }
}
