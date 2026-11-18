package com.vodplatform.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.vodplatform.catalog.persistence.MovieRepository;
import com.vodplatform.common.support.MovieVisibilityRule;
import com.vodplatform.common.support.PostgresTestDatabase;
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
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
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
    @Autowired JwtEncoder encoder;
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
        for(UUID id:movieIds) jdbc.update("delete from movies where id=?",id);
        for(UUID id:userIds) jdbc.update("delete from users where id=?",id);
    }

    @Test void exactEmptyAndSavedResponsesUseAssetDurationAndMicroseconds() throws Exception {
        mvc.perform(get(path(movie)).header("Authorization",token)).andExpect(status().isOk())
            .andExpect(content().json("""
                {"movieId":"%s","currentSeconds":0,"durationSeconds":null,"finished":false,"lastPlayedAt":null}
                """.formatted(movie),JsonCompareMode.STRICT));
        var saved=save(movie,asset,40,false,token);
        saved.andExpect(status().isOk()).andExpect(content().json("""
                {"movieId":"%s","currentSeconds":40,"durationSeconds":100,"finished":false,
                 "lastPlayedAt":"2026-11-01T00:00:00.123456Z"}
                """.formatted(movie),JsonCompareMode.STRICT));
        assertThat(progress(user,movie).get("duration_seconds")).isEqualTo(100);
        assertThat(time(progress(user,movie),"last_played_at")).isEqualTo(T0.truncatedTo(ChronoUnit.MICROS));
        assertThat(jdbc.queryForObject("select count(*) from watch_history where user_id=? and movie_id=?",Integer.class,user,movie)).isEqualTo(1);
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
            jdbc.update("insert into playback_progress(user_id,movie_id,current_seconds,finished,last_played_at,created_at,updated_at) values(?,?,20,false,?,?,?)",
                user,hidden,Timestamp.from(T0),Timestamp.from(T0),Timestamp.from(T0));
        }
        String auth=token(user,role);
        var before=jdbc.queryForList("select * from playback_progress where user_id=? order by movie_id",user);
        MovieVisibilityRule.assertHiddenLikeMissing(f,id -> mvc.perform(get(path(id)).header("Authorization",auth)));
        MovieVisibilityRule.assertHiddenLikeMissing(f,id -> save(id,visibleAsset,30,false,auth));
        assertThat(jdbc.queryForList("select * from playback_progress where user_id=? order by movie_id",user)).isEqualTo(before);
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
        jdbc.update("delete from video_assets where movie_id=?",movie);
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

    @Test void simultaneousHttpWritesKeepLaterServerTimeWithoutDeadlock() throws Exception {
        var barrier=new CyclicBarrier(2);
        when(clock.instant()).thenAnswer(call -> {
            if(threadTime.get()!=null) {barrier.await(15,TimeUnit.SECONDS); return threadTime.get();}
            return now.get();
        });
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=new ArrayList<Future<MvcResult>>();
            for(int i=0;i<2;i++) { final int index=i; futures.add(pool.submit(() -> {
                threadTime.set(T0.plusSeconds(index+1));
                try { return save(movie,asset,index==0?95:40,false,token).andExpect(status().isOk()).andReturn(); }
                finally {threadTime.remove();}
            })); }
            var older=mapper.readTree(futures.get(0).get(30,TimeUnit.SECONDS).getResponse().getContentAsString());
            var later=mapper.readTree(futures.get(1).get(30,TimeUnit.SECONDS).getResponse().getContentAsString());
            assertThat(later.path("currentSeconds").asInt()).isEqualTo(40);
            assertThat(later.path("lastPlayedAt").asString())
                .isEqualTo(T0.plusSeconds(2).truncatedTo(ChronoUnit.MICROS).toString());
            if(older.path("currentSeconds").asInt()==95) {
                assertThat(time(history(user,movie),"completed_at"))
                    .isEqualTo(T0.plusSeconds(1).truncatedTo(ChronoUnit.MICROS));
            } else {
                assertThat(older).isEqualTo(later);
                assertThat(history(user,movie).get("completed_at")).isNull();
            }
        }
        assertThat(progress(user,movie).get("current_seconds")).isEqualTo(40);
        assertThat(time(progress(user,movie),"last_played_at")).isEqualTo(T0.plusSeconds(2).truncatedTo(ChronoUnit.MICROS));
        assertThat(time(history(user,movie),"last_watched_at")).isEqualTo(T0.plusSeconds(2).truncatedTo(ChronoUnit.MICROS));
        assertThat(progressCount()).isEqualTo(1); assertThat(historyCount()).isEqualTo(1);
    }

    private ResultActions save(UUID id,UUID video,int position,boolean finished,String auth) throws Exception {
        return mvc.perform(put(path(id)).header("Authorization",auth).contentType(MediaType.APPLICATION_JSON).content(body(video,position,finished)));
    }
    private String body(UUID id,int position,boolean finished) {return "{\"videoAssetId\":\""+id+"\",\"currentSeconds\":"+position+",\"durationSeconds\":999,\"finished\":"+finished+"}";}
    private static String path(Object id) {return "/api/v1/movies/"+id+"/progress";}
    private Map<String,Object> progress(UUID u,UUID m) {return jdbc.queryForMap("select * from playback_progress where user_id=? and movie_id=?",u,m);}
    private Map<String,Object> history(UUID u,UUID m) {return jdbc.queryForMap("select * from watch_history where user_id=? and movie_id=?",u,m);}
    private Instant time(Map<String,Object> row,String col) {return ((Timestamp)row.get(col)).toInstant();}
    private int progressCount() {return jdbc.queryForObject("select count(*) from playback_progress where user_id=?",Integer.class,user);}
    private int historyCount() {return jdbc.queryForObject("select count(*) from watch_history where user_id=?",Integer.class,user);}
    private UUID user() {UUID id=UUID.randomUUID();userIds.add(id);jdbc.update("insert into users(id,email,password_hash,display_name,status,created_at,updated_at) values(?,?,'unused','Progress fixture','ACTIVE',?,?)",id,id+"@example.test",Timestamp.from(T0),Timestamp.from(T0));return id;}
    private UUID movie() {UUID id=UUID.randomUUID();movieIds.add(id);jdbc.update("insert into movies(id,title,slug,description,status,created_at,updated_at) values(?,'Progress fixture',?,'Progress fixture','PUBLISHED',?,?)",id,"progress-"+id,Timestamp.from(T0),Timestamp.from(T0));return id;}
    private UUID asset(UUID movie,String state,Integer duration,int second) {UUID id=UUID.randomUUID();jdbc.update("insert into video_assets(id,movie_id,raw_bucket,raw_object_key,status,duration_seconds,created_at,updated_at) values(?,?,'fixture',?,?,?,?,?)",id,movie,id.toString(),state,duration,Timestamp.from(T0.plusSeconds(second)),Timestamp.from(T0));return id;}
    private String token(UUID user,String role) {Instant time=Instant.now();var claims=JwtClaimsSet.builder().subject(user.toString()).issuedAt(time).expiresAt(time.plusSeconds(600)).claim("userId",user.toString()).claim("email","progress@example.test").claim("roles",List.of(role)).build();return "Bearer "+encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();}
}
