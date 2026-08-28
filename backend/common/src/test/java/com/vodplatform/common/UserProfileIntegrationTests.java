package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

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
