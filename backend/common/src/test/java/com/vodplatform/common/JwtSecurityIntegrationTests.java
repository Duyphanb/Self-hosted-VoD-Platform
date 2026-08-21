package com.vodplatform.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
        properties = {
                "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
                "auth.tokens.access-token-ttl=15m",
                "auth.tokens.refresh-token-ttl=7d"
        }
)
@AutoConfigureMockMvc
@Import(JwtSecurityIntegrationTests.SecurityProbeController.class)
class JwtSecurityIntegrationTests {

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
    private JwtEncoder jwtEncoder;

    private UUID userId;

    @BeforeEach
    void createRegisteredUser() {
        cleanDatabase();
        jdbcTemplate.update("INSERT INTO roles (name) VALUES (?)", "ROLE_USER");
        RoleEntity role = roleRepository.findByName("ROLE_USER").orElseThrow();
        Instant now = Instant.now();
        userId = UUID.randomUUID();
        UserEntity user = new UserEntity(
                userId,
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
    void anonymousMalformedAndTamperedBearerTokensReturnGenericUnauthorized() throws Exception {
        assertUnauthorized(null);
        assertUnauthorized("Bearer not-a-jwt");
        assertUnauthorized("Bearer " + tamperSignature(loginAndReadTokens().path("accessToken").asText()));
    }

    @Test
    void validBearerTokenPopulatesUserAndRolesInSecurityContext() throws Exception {
        String accessToken = loginAndReadTokens().path("accessToken").asText();

        mockMvc.perform(get("/test/security-context")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principal.userId").value(userId.toString()))
                .andExpect(jsonPath("$.principal.email").value("viewer@example.com"))
                .andExpect(jsonPath("$.principal.roles[0]").value("ROLE_USER"))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_USER"));
    }

    @Test
    void expiredBearerTokenReturnsUnauthorized() throws Exception {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuedAt(now.minusSeconds(600))
                .expiresAt(now.minusSeconds(300))
                .claim("userId", userId.toString())
                .claim("email", "viewer@example.com")
                .claim("roles", List.of("ROLE_USER"))
                .build();
        String expiredToken = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims
        )).getTokenValue();

        assertUnauthorized("Bearer " + expiredToken);
    }

    @Test
    void signedTokenWithoutRequiredRolesClaimReturnsUnauthorized() throws Exception {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("userId", userId.toString())
                .claim("email", "viewer@example.com")
                .build();
        String incompleteToken = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims
        )).getTokenValue();

        assertUnauthorized("Bearer " + incompleteToken);
    }

    @Test
    void signedTokenWithoutExpirationReturnsUnauthorized() throws Exception {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .claim("userId", userId.toString())
                .claim("email", "viewer@example.com")
                .claim("roles", List.of("ROLE_USER"))
                .build();
        String nonExpiringToken = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims
        )).getTokenValue();

        assertUnauthorized("Bearer " + nonExpiringToken);
    }

    @Test
    void publicOperationsBypassInvalidBearerValidation() throws Exception {
        String invalidAuthorization = "Bearer not-a-jwt";

        mockMvc.perform(get("/api/v1/health")
                        .header(HttpHeaders.AUTHORIZATION, invalidAuthorization))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.AUTHORIZATION, invalidAuthorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "new-viewer@example.com",
                                  "password": "another-strong-password",
                                  "displayName": "New Viewer"
                                }
                                """))
                .andExpect(status().isCreated());

        JsonNode login = loginAndReadTokens(invalidAuthorization);
        JsonNode refresh = performAuthRequest(
                "/api/v1/auth/refresh",
                objectMapper.createObjectNode().put("refreshToken", login.path("refreshToken").asText()),
                invalidAuthorization,
                200
        );
        performAuthRequest(
                "/api/v1/auth/logout",
                objectMapper.createObjectNode().put("refreshToken", refresh.path("refreshToken").asText()),
                invalidAuthorization,
                204
        );
    }

    private void assertUnauthorized(String authorization) throws Exception {
        var request = get("/test/security-context");
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Authentication required or invalid token"));
    }

    private JsonNode loginAndReadTokens() throws Exception {
        return loginAndReadTokens(null);
    }

    private JsonNode loginAndReadTokens(String authorization) throws Exception {
        return performAuthRequest(
                "/api/v1/auth/login",
                objectMapper.createObjectNode()
                        .put("email", "viewer@example.com")
                        .put("password", "strong-password"),
                authorization,
                200
        );
    }

    private JsonNode performAuthRequest(
            String path,
            JsonNode body,
            String authorization,
            int expectedStatus
    ) throws Exception {
        var request = post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body));
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn();
        byte[] responseBody = result.getResponse().getContentAsByteArray();
        return responseBody.length == 0 ? objectMapper.createObjectNode() : objectMapper.readTree(responseBody);
    }

    private String tamperSignature(String token) {
        int signatureStart = token.lastIndexOf('.') + 1;
        char firstSignatureCharacter = token.charAt(signatureStart);
        char replacement = firstSignatureCharacter == 'A' ? 'B' : 'A';
        return token.substring(0, signatureStart)
                + replacement
                + token.substring(signatureStart + 1);
    }

    @RestController
    static class SecurityProbeController {

        @GetMapping("/test/security-context")
        SecurityContextSnapshot securityContext(Authentication authentication) {
            List<String> authorities = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .sorted()
                    .toList();
            return new SecurityContextSnapshot(authentication.getPrincipal(), authorities);
        }
    }

    record SecurityContextSnapshot(Object principal, List<String> authorities) {
    }
}
