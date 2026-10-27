package com.vodplatform.common;

import static com.vodplatform.common.contract.ContractValidation.offContract;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The application-wide UUID binding through the assembled application: a UUID request parameter
 * or JSON body property is accepted in canonical 8-4-4-4-12 form only. The API's own path
 * variables are covered by the movie, genre and person API tests; no API operation reads a UUID
 * from a query parameter or a request body yet, so a test-only probe controller stands in.
 */
@SpringBootTest(
        properties = {
                "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
                "auth.tokens.access-token-ttl=15m",
                "auth.tokens.refresh-token-ttl=7d"
        }
)
@AutoConfigureMockMvc
@Import(UuidBindingIntegrationTests.UuidProbeController.class)
class UuidBindingIntegrationTests {

    private static final RequestPostProcessor PROBE = offContract(
            "test-only probe controller for UUID binding; /test/uuids is not an API operation");
    private static final String CANONICAL = "123e4567-e89b-12d3-a456-426614174000";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Test
    void aQueryParameterUuidMustBeInCanonicalForm() throws Exception {
        String user = bearerToken();
        perform(get("/test/uuids").param("id", CANONICAL.toUpperCase()), user)
                .andExpect(status().isOk())
                .andExpect(content().string(CANONICAL));

        for (String nonCanonical : List.of("1-1-1-1-1", " " + CANONICAL)) {
            perform(get("/test/uuids").param("id", nonCanonical), user)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("id"))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value("must be a valid UUID"))
                    .andExpect(jsonPath("$.fieldErrors.length()").value(1));
        }
    }

    @Test
    void aJsonBodyUuidMustBeInCanonicalForm() throws Exception {
        String user = bearerToken();
        perform(body("{\"id\": \"" + CANONICAL.toUpperCase() + "\"}"), user)
                .andExpect(status().isOk())
                .andExpect(content().string(CANONICAL));

        // Jackson's own UUID deserializer accepts all three: padded text and Base64 forms.
        for (String nonCanonical : List.of(" " + CANONICAL + " ", "EjRWeJq83vAAERIjNEVWZw==", "EjRWeJq83vAAERIjNEVWZw")) {
            perform(body("{\"id\": \"" + nonCanonical + "\"}"), user)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.message").value("Request body is missing or malformed"))
                    .andExpect(jsonPath("$.fieldErrors").doesNotExist())
                    .andExpect(content().string(not(containsString(nonCanonical.trim()))));
        }
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String authorization) throws Exception {
        return mockMvc.perform(request.with(PROBE).header(HttpHeaders.AUTHORIZATION, authorization));
    }

    private static MockHttpServletRequestBuilder body(String json) {
        return post("/test/uuids").contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private String bearerToken() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("userId", userId.toString())
                .claim("email", "uuid-binding-test@example.com")
                .claim("roles", List.of("ROLE_USER"))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims
        )).getTokenValue();
        return "Bearer " + token;
    }

    @RestController
    static class UuidProbeController {

        @GetMapping("/test/uuids")
        String query(@RequestParam("id") UUID id) {
            return id.toString();
        }

        @PostMapping("/test/uuids")
        String body(@RequestBody UuidBody body) {
            return body.id().toString();
        }
    }

    record UuidBody(UUID id) {
    }
}
