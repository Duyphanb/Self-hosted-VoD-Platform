package com.vodplatform.common.contract;

import static com.vodplatform.common.contract.ContractValidation.offContract;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves that the auto-configured {@code MockMvc} validates every response against the contract
 * on its own, through {@link ContractValidationAutoConfiguration} and its entry in
 * {@code META-INF/spring/org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc.imports}.
 * Without that entry the contract check of every integration test would stop silently, so nothing
 * here attaches {@link ContractValidation#matchesContract()}.
 *
 * <p>The probe path has no handler and lies outside the {@code /api/v1} and {@code /hls} trees of
 * the contract, so no real controller can answer it: the security chain rejects the anonymous
 * request with 401, and the contract does not declare the path. The context configuration is the
 * one of the other {@code @AutoConfigureMockMvc} tests, so the cached context is reused.
 */
@SpringBootTest(
        properties = {
                "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
                "auth.tokens.access-token-ttl=15m",
                "auth.tokens.refresh-token-ttl=7d"
        }
)
@AutoConfigureMockMvc
class ContractValidationAutoConfigurationTests {

    private static final String PROBE_PATH = "/test/contract-autowire-probe";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void autoConfiguredMockMvcRejectsAResponseOutsideTheContractWithoutAnyExplicitMatcher() {
        assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(() -> mockMvc.perform(get(PROBE_PATH)))
                .withMessageContaining("Response of GET " + PROBE_PATH)
                .withMessageContaining("does not match docs/architecture/API-CONTRACT.yaml")
                .withMessageContaining("path " + PROBE_PATH + " is not declared");
    }

    @Test
    void autoConfiguredMockMvcSkipsARequestMarkedOffContract() throws Exception {
        mockMvc.perform(get(PROBE_PATH).with(offContract(
                        "probe for the automatic contract check; " + PROBE_PATH + " is not an API operation")))
                .andExpect(status().isUnauthorized());
    }
}
