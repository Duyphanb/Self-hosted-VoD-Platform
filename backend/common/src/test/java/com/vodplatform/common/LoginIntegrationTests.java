package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vodplatform.auth.persistence.RefreshTokenEntity;
import com.vodplatform.auth.persistence.RefreshTokenRepository;
import com.vodplatform.auth.persistence.RoleEntity;
import com.vodplatform.auth.persistence.RoleRepository;
import com.vodplatform.auth.persistence.UserEntity;
import com.vodplatform.auth.persistence.UserRepository;
import com.vodplatform.auth.persistence.UserStatus;
import com.vodplatform.auth.service.RefreshTokenService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
        properties = {
                "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
                "auth.tokens.access-token-ttl=15m",
                "auth.tokens.refresh-token-ttl=7d"
        }
)
@AutoConfigureMockMvc
@Transactional
class LoginIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void createRegisteredUser() {
        jdbcTemplate.update("INSERT INTO roles (name) VALUES (?)", "ROLE_USER");
        RoleEntity role = roleRepository.findByName("ROLE_USER").orElseThrow();
        Instant now = Instant.now();
        UserEntity user = new UserEntity(
                UUID.randomUUID(),
                "viewer@example.com",
                passwordEncoder.encode("strong-password"),
                "Viewer",
                UserStatus.ACTIVE,
                now,
                now
        );
        user.addRole(role);
        userRepository.saveAndFlush(user);
    }

    @Test
    void loginReturnsTokensAndPersistsOnlyRefreshTokenHash() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "viewer@example.com",
                                  "password": "strong-password"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("viewer@example.com"))
                .andExpect(jsonPath("$.user.roles[0]").value("ROLE_USER"))
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.expiresInSeconds").value(900))
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        String rawRefreshToken = response.path("refreshToken").asText();
        List<RefreshTokenEntity> storedTokens = refreshTokenRepository.findAll();
        assertThat(storedTokens).hasSize(1);
        RefreshTokenEntity storedToken = storedTokens.getFirst();
        assertThat(storedToken.getTokenHash()).isEqualTo(refreshTokenService.hash(rawRefreshToken));
        assertThat(storedToken.getTokenHash()).isNotEqualTo(rawRefreshToken);
        assertThat(storedToken.getExpiresAt()).isAfter(Instant.now().plusSeconds(6 * 24 * 60 * 60));
        assertThat(storedToken.getRevokedAt()).isNull();
    }

    @Test
    void unknownEmailAndWrongPasswordReturnIndistinguishableUnauthorizedErrors() throws Exception {
        String wrongPasswordResponse = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "viewer@example.com",
                                  "password": "wrong-password"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String unknownEmailResponse = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "missing@example.com",
                                  "password": "wrong-password"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode wrongPasswordError = objectMapper.readTree(wrongPasswordResponse);
        JsonNode unknownEmailError = objectMapper.readTree(unknownEmailResponse);
        assertThat(wrongPasswordError.path("status")).isEqualTo(unknownEmailError.path("status"));
        assertThat(wrongPasswordError.path("code")).isEqualTo(unknownEmailError.path("code"));
        assertThat(wrongPasswordError.path("message")).isEqualTo(unknownEmailError.path("message"));
    }
}
