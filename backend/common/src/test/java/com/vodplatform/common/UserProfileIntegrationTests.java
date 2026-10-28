package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vodplatform.auth.persistence.RefreshTokenRepository;
import com.vodplatform.auth.persistence.RoleEntity;
import com.vodplatform.auth.persistence.RoleRepository;
import com.vodplatform.auth.persistence.UserEntity;
import com.vodplatform.auth.persistence.UserRepository;
import com.vodplatform.auth.persistence.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        properties = {
                "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
                "auth.tokens.access-token-ttl=15m",
                "auth.tokens.refresh-token-ttl=7d"
        }
)
@AutoConfigureMockMvc
class UserProfileIntegrationTests {

    @Autowired
    private ObjectMapper objectMapper;

    @ParameterizedTest
    @MethodSource("invalidDisplayNames")
    void rejectsInvalidDisplayNamesOnBothEndpointsWithoutWriting(String name) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "invalid-name@example.com", "password", "strong-password", "displayName", name))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("displayName"));
        assertThat(userRepository.findByEmail("invalid-name@example.com")).isEmpty();

        mockMvc.perform(put("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("displayName", name))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("displayName"));
        assertThat(userRepository.findById(currentUser.getId()).orElseThrow().getDisplayName())
                .isEqualTo("Current Viewer");
    }

    static Stream<String> invalidDisplayNames() {
        return Stream.of("  ", "\u00a0\u2007\u202f", "\u0000Viewer", "Viewer\u0000", "View\rer", "View\ner",
                "\tViewer", "View\u007fer", "View\u009fer", " A ", "😀", " 😀 ",
                " " + "A".repeat(100) + " ", " ".repeat(10000) + "AB", "😀".repeat(101));
    }

    @ParameterizedTest
    // H2 varchar counts UTF-16 units; PostgreSQL 51/100-code-point persistence is in auth-runtime-smoke.py.
    @ValueSource(ints = {2, 50})
    void acceptsAstralNamesOnBothEndpointsWithinH2Capacity(int count) throws Exception {
        String name = "😀".repeat(count);
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "emoji@example.com", "password", "strong-password", "displayName", name))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value(name));
        assertThat(userRepository.findByEmail("emoji@example.com").orElseThrow().getDisplayName()).isEqualTo(name);
        mockMvc.perform(put("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("displayName", name))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value(name));
        assertThat(userRepository.findById(currentUser.getId()).orElseThrow().getDisplayName()).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AB", "Tên  Việt", "A\u200b\u202e\ufeffB"})
    void trimsNamesOnBothEndpointsAndPreservesInternalSpacingAndCf(String name) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "trimmed@example.com", "password", "strong-password", "displayName", "\u00a0 " + name + " \u202f"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value(name));
        assertThat(userRepository.findByEmail("trimmed@example.com").orElseThrow().getDisplayName()).isEqualTo(name);
        mockMvc.perform(put("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("displayName", "\u2007 " + name + " \u3000"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value(name));
        assertThat(userRepository.findById(currentUser.getId()).orElseThrow().getDisplayName()).isEqualTo(name);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JwtEncoder jwtEncoder;

    private UserEntity currentUser;
    private UserEntity otherUser;
    private String accessToken;

    @BeforeEach
    void createUsers() {
        cleanDatabase();
        jdbcTemplate.update("INSERT INTO roles (name) VALUES (?)", "ROLE_USER");
        RoleEntity role = roleRepository.findByName("ROLE_USER").orElseThrow();
        Instant now = Instant.now();
        currentUser = new UserEntity(
                UUID.randomUUID(),
                "current@example.com",
                "current-password-hash",
                "Current Viewer",
                UserStatus.ACTIVE,
                now,
                now
        );
        currentUser.addRole(role);
        otherUser = new UserEntity(
                UUID.randomUUID(),
                "other@example.com",
                "other-password-hash",
                "Other Viewer",
                UserStatus.ACTIVE,
                now,
                now
        );
        otherUser.addRole(role);
        userRepository.saveAllAndFlush(List.of(currentUser, otherUser));
        accessToken = tokenFor(currentUser);
    }

    @AfterEach
    void cleanDatabase() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();
    }

    @Test
    void getsOnlyAuthenticatedUsersProfile() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(currentUser.getId().toString()))
                .andExpect(jsonPath("$.email").value("current@example.com"))
                .andExpect(jsonPath("$.displayName").value("Current Viewer"))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist());
    }

    @Test
    void updatesOnlyAuthenticatedUsersDisplayName() throws Exception {
        mockMvc.perform(put("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Updated Viewer",
                                  "email": "attacker@example.com",
                                  "password": "replacement-password",
                                  "roles": ["ROLE_ADMIN"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(currentUser.getId().toString()))
                .andExpect(jsonPath("$.email").value("current@example.com"))
                .andExpect(jsonPath("$.displayName").value("Updated Viewer"))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"));

        UserEntity persistedCurrent = userRepository.findById(currentUser.getId()).orElseThrow();
        UserEntity persistedOther = userRepository.findById(otherUser.getId()).orElseThrow();
        assertThat(persistedCurrent.getDisplayName()).isEqualTo("Updated Viewer");
        assertThat(persistedCurrent.getEmail()).isEqualTo("current@example.com");
        assertThat(persistedCurrent.getPasswordHash()).isEqualTo("current-password-hash");
        assertThat(persistedCurrent.getRoles()).extracting(RoleEntity::getName).containsExactly("ROLE_USER");
        assertThat(persistedOther.getDisplayName()).isEqualTo("Other Viewer");
        assertThat(persistedOther.getEmail()).isEqualTo("other@example.com");
        assertThat(persistedOther.getPasswordHash()).isEqualTo("other-password-hash");
    }

    @Test
    void validatesDisplayNameAndRequiresAuthentication() throws Exception {
        mockMvc.perform(put("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"A\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("displayName"));

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Updated Viewer\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mapsValidTokenForInactiveUserToUnauthorizedApiError() throws Exception {
        Instant now = Instant.now();
        UserEntity disabledUser = new UserEntity(
                UUID.randomUUID(),
                "disabled@example.com",
                "disabled-password-hash",
                "Disabled Viewer",
                UserStatus.DISABLED,
                now,
                now
        );
        disabledUser.addRole(roleRepository.findByName("ROLE_USER").orElseThrow());
        userRepository.saveAndFlush(disabledUser);

        // The token itself is valid, so the 401 comes from the auth exception advice applied to
        // the user controller, not from the security entry point (which adds WWW-Authenticate).
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(disabledUser)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Authentication required or invalid token"));
    }

    private String tokenFor(UserEntity user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("userId", user.getId().toString())
                .claim("email", user.getEmail())
                .claim("roles", List.of("ROLE_USER"))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims
        )).getTokenValue();
    }

}
