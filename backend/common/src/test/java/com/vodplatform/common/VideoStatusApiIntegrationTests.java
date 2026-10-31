package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vodplatform.catalog.persistence.Movie;
import com.vodplatform.catalog.persistence.MovieRepository;
import com.vodplatform.catalog.persistence.MovieSort;
import com.vodplatform.catalog.persistence.MovieStatus;
import com.vodplatform.catalog.service.MovieService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Real encoding port through assembled catalog/security/HTTP, with automatic contract validation. */
@SpringBootTest(properties = {
        "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
        "auth.tokens.access-token-ttl=15m", "auth.tokens.refresh-token-ttl=7d"
})
@AutoConfigureMockMvc
@Transactional
class VideoStatusApiIntegrationTests {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private static final String BODY = """
            {"title":"Availability fixture","slug":"availability-fixture","description":"Test movie",
             "releaseYear":2026,"maturityRating":null,"genres":["Drama"],"people":[],"status":"PUBLISHED"}
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private MovieRepository movies;
    @Autowired private MovieService movieService;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void olderReadySurvivesReplacementForBothRolesListsDetailAndPut() throws Exception {
        UUID movie = movie(MovieStatus.PUBLISHED, 0);
        asset(movie, "READY", T0);
        for (String replacement : List.of("QUEUED", "PROCESSING", "FAILED")) {
            UUID pending = asset(movie, replacement, T0.plusSeconds(1));
            for (String role : List.of("ROLE_USER", "ROLE_ADMIN")) {
                String bearer = bearer(role);
                mockMvc.perform(get("/api/v1/movies").header(HttpHeaders.AUTHORIZATION, bearer))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].videoStatus").value("READY"));
                mockMvc.perform(get("/api/v1/movies/" + movie).header(HttpHeaders.AUTHORIZATION, bearer))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.videoStatus").value("READY"));
            }
            mockMvc.perform(get("/api/v1/admin/movies").header(HttpHeaders.AUTHORIZATION, bearer("ROLE_ADMIN")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].videoStatus").value("READY"));
            mockMvc.perform(put("/api/v1/movies/" + movie).header(HttpHeaders.AUTHORIZATION, bearer("ROLE_ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.videoStatus").value("READY"));
            jdbc.update("delete from video_assets where id = ?", pending);
        }
    }

    @Test
    void missingAssetsAreNullAndNewestPendingIsReturnedWithoutRoleMasking() throws Exception {
        UUID movie = movie(MovieStatus.DRAFT, 0);
        String admin = bearer("ROLE_ADMIN");
        mockMvc.perform(get("/api/v1/movies/" + movie).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.videoStatus").isEmpty());
        asset(movie, "FAILED", T0);
        asset(movie, "QUEUED", T0.plusSeconds(1));
        mockMvc.perform(get("/api/v1/admin/movies").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].videoStatus").value("QUEUED"));
        mockMvc.perform(get("/api/v1/movies/" + movie).header(HttpHeaders.AUTHORIZATION, bearer("ROLE_USER")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/movies").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.videoStatus").isEmpty());
    }

    @Test
    void fullWiringUsesFourStatementsForBothSmallAndLargeFullPages() {
        for (int index = 0; index < 9; index++) {
            UUID movie = movie(MovieStatus.PUBLISHED, index);
            asset(movie, "READY", T0);
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            for (int pageSize : List.of(2, 8)) {
                entityManager.clear();
                statistics.clear();
                assertThat(movieService.listPublished(null, null, 0, pageSize, MovieSort.LATEST).getContent())
                        .hasSize(pageSize).allSatisfy(movie -> assertThat(movie.videoStatus()).isEqualTo("READY"));
                assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
            }
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }

    private UUID movie(MovieStatus status, int second) {
        UUID id = UUID.randomUUID();
        movies.saveAndFlush(new Movie(id, "Fixture", "fixture-" + id, "Test movie", 2026,
                null, status, T0.plusSeconds(second)));
        entityManager.clear();
        return id;
    }

    private UUID asset(UUID movie, String status, Instant created) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into video_assets (id,movie_id,raw_bucket,raw_object_key,status,created_at,updated_at) "
                        + "values (?,?,'fixture',?,?,?,?)", id, movie, id.toString(), status,
                Timestamp.from(created), Timestamp.from(created));
        return id;
    }

    private String bearer(String role) {
        UUID user = UUID.randomUUID();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(user.toString()).issuedAt(now)
                .expiresAt(now.plusSeconds(300)).claim("userId", user.toString())
                .claim("email", "availability@example.test").claim("roles", List.of(role)).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
    }
}
