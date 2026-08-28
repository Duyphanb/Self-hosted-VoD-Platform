package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class LogoutIntegrationTests {

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
    void submittedRefreshTokenIsRevokedAndReturnsNoContent() throws Exception {
        String rawRefreshToken = loginAndReadRefreshToken();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                objectMapper.createObjectNode().put("refreshToken", rawRefreshToken)
                        )))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        String storedHash = refreshTokenService.hash(rawRefreshToken);
        RefreshTokenEntity storedToken = refreshTokenRepository.findAll().stream()
                .filter(token -> token.getTokenHash().equals(storedHash))
                .findFirst()
                .orElseThrow();
        assertThat(storedToken.getRevokedAt()).isNotNull();
    }

    @Test
    void replayedUnknownAndOmittedTokensAllReturnNoContent() throws Exception {
        String rawRefreshToken = loginAndReadRefreshToken();
        byte[] knownTokenBody = logoutBody(rawRefreshToken);
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(knownTokenBody))
                .andExpect(status().isNoContent());
        Instant originalRevocation = findStoredToken(rawRefreshToken).getRevokedAt();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(knownTokenBody))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(logoutBody("unknown-token")))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isNoContent());

        assertThat(findStoredToken(rawRefreshToken).getRevokedAt()).isEqualTo(originalRevocation);
    }

    @Test
    void loggedOutRefreshTokenCannotBeRotated() throws Exception {
        String rawRefreshToken = loginAndReadRefreshToken();
        byte[] requestBody = logoutBody(rawRefreshToken);
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    private byte[] logoutBody(String refreshToken) throws Exception {
        return objectMapper.writeValueAsBytes(
                objectMapper.createObjectNode().put("refreshToken", refreshToken)
        );
    }

    private RefreshTokenEntity findStoredToken(String rawRefreshToken) {
        String storedHash = refreshTokenService.hash(rawRefreshToken);
        return refreshTokenRepository.findAll().stream()
                .filter(token -> token.getTokenHash().equals(storedHash))
                .findFirst()
                .orElseThrow();
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
