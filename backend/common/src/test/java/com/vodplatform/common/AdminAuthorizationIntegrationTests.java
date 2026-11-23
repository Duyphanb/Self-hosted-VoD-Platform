package com.vodplatform.common;

import static com.vodplatform.common.contract.ContractValidation.offContract;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vodplatform.common.support.BearerSessionFixture;
import com.vodplatform.common.support.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
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
class AdminAuthorizationIntegrationTests implements PostgresTestDatabase {

    private static final RequestPostProcessor PROBE = offContract(
            "test-only probe controller for method security; /test/admin-authorization is not an API operation");

    @Autowired
    private MockMvc mockMvc;

    @RegisterExtension
    final BearerSessionFixture bearerSessions = new BearerSessionFixture();

    @Test
    void roleUserReceivesForbiddenForAdminOperation() throws Exception {
        mockMvc.perform(get("/test/admin-authorization").with(PROBE)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken("ROLE_USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message")
                        .value("Authenticated user is not allowed to perform this action"));
    }

    @Test
    void roleAdminCanAccessAdminOperation() throws Exception {
        mockMvc.perform(get("/test/admin-authorization").with(PROBE)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken("ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("allowed"));
    }

    @Test
    void anonymousRequestStillReceivesUnauthorized() throws Exception {
        mockMvc.perform(get("/test/admin-authorization").with(PROBE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private String bearerToken(String role) {
        return "Bearer " + bearerSessions.token(role);
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
