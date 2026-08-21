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
import org.junit.jupiter.api.AfterEach;
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

@SpringBootTest(
        properties = {
                "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
                "auth.tokens.access-token-ttl=15m",
                "auth.tokens.refresh-token-ttl=7d"
        }
)
@AutoConfigureMockMvc
class RefreshTokenIntegrationTests {

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
        cleanDatabase();
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

    @AfterEach
    void cleanDatabase() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();
    }

    @Test
    void validRefreshTokenRotatesAndReturnsNewTokens() throws Exception {
        String originalRefreshToken = loginAndReadRefreshToken();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                objectMapper.createObjectNode().put("refreshToken", originalRefreshToken)
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("viewer@example.com"))
                .andExpect(jsonPath("$.user.roles[0]").value("ROLE_USER"))
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.expiresInSeconds").value(900))
                .andReturn();

        String rotatedRefreshToken = objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("refreshToken")
                .asText();
        assertThat(rotatedRefreshToken).isNotEqualTo(originalRefreshToken);

        List<RefreshTokenEntity> storedTokens = refreshTokenRepository.findAll();
        assertThat(storedTokens).hasSize(2);
        RefreshTokenEntity originalStoredToken = storedTokens.stream()
                .filter(token -> token.getTokenHash().equals(refreshTokenService.hash(originalRefreshToken)))
                .findFirst()
                .orElseThrow();
        RefreshTokenEntity rotatedStoredToken = storedTokens.stream()
                .filter(token -> token.getTokenHash().equals(refreshTokenService.hash(rotatedRefreshToken)))
                .findFirst()
                .orElseThrow();
        assertThat(originalStoredToken.getRevokedAt()).isNotNull();
        assertThat(rotatedStoredToken.getRevokedAt()).isNull();
        assertThat(storedTokens)
                .extracting(RefreshTokenEntity::getTokenHash)
                .doesNotContain(originalRefreshToken, rotatedRefreshToken);
    }

    @Test
    void replayedAndUnknownRefreshTokensReturnIndistinguishableUnauthorizedErrors() throws Exception {
        String originalRefreshToken = loginAndReadRefreshToken();
        byte[] requestBody = objectMapper.writeValueAsBytes(
                objectMapper.createObjectNode().put("refreshToken", originalRefreshToken)
        );
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk());

        String replayedResponse = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
                .andExpect(jsonPath("$.message").value("Invalid refresh token"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String unknownResponse = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"unknown-refresh-token"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode replayedError = objectMapper.readTree(replayedResponse);
        JsonNode unknownError = objectMapper.readTree(unknownResponse);
        assertThat(replayedError.path("status")).isEqualTo(unknownError.path("status"));
        assertThat(replayedError.path("code")).isEqualTo(unknownError.path("code"));
        assertThat(replayedError.path("message")).isEqualTo(unknownError.path("message"));
    }

    @Test
    void blankRefreshTokenFailsRequestValidation() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":" "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private String loginAndReadRefreshToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "viewer@example.com",
                                  "password": "strong-password"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("refreshToken")
                .asText();
    }
}
