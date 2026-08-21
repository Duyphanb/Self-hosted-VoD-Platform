package com.vodplatform.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
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
@Import(AdminAuthorizationIntegrationTests.AdminAuthorizationProbeController.class)
class AdminAuthorizationIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Test
    void roleUserReceivesForbiddenForAdminOperation() throws Exception {
        mockMvc.perform(get("/test/admin-authorization")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken("ROLE_USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message")
                        .value("Authenticated user is not allowed to perform this action"));
    }

    @Test
    void roleAdminCanAccessAdminOperation() throws Exception {
        mockMvc.perform(get("/test/admin-authorization")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken("ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("allowed"));
    }

    @Test
    void anonymousRequestStillReceivesUnauthorized() throws Exception {
        mockMvc.perform(get("/test/admin-authorization"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private String bearerToken(String role) {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("userId", userId.toString())
                .claim("email", "rbac-test@example.com")
                .claim("roles", List.of(role))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims
        )).getTokenValue();
        return "Bearer " + token;
    }

    @RestController
    static class AdminAuthorizationProbeController {

        @GetMapping("/test/admin-authorization")
        @PreAuthorize("hasRole('ADMIN')")
        AdminAuthorizationResponse adminOnly() {
            return new AdminAuthorizationResponse("allowed");
        }
    }

    record AdminAuthorizationResponse(String status) {
    }
}
