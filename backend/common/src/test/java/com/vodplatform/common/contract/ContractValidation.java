package com.vodplatform.common.contract;

import java.util.List;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.Assert;

/**
 * MockMvc helpers for {@link ApiContract}.
 *
 * <p>Every full-context test in this module that uses the auto-configured {@code MockMvc}
 * ({@code @AutoConfigureMockMvc}) already checks each response with {@link #matchesContract()}
 * through {@link ContractValidationAutoConfiguration}; new API tests get the check without any
 * setup. A request that deliberately leaves the contract (a test-only probe controller, a CORS
 * preflight, a method the contract does not declare) is marked with {@link #offContract(String)}
 * and a reason. Do not use it to hide a response that should conform: fix the response, or update
 * the contract first.
 */
public final class ContractValidation {

    static final String OFF_CONTRACT_REASON = ContractValidation.class.getName() + ".offContractReason";

    private ContractValidation() {
    }

    /** Asserts that the response conforms to {@code docs/architecture/API-CONTRACT.yaml}. */
    public static ResultMatcher matchesContract() {
        return ContractValidation::assertMatchesContract;
    }

    /**
     * Marks a request that deliberately leaves the contract, so its response is not validated.
     *
     * @param reason why the request is not a contract operation; shown to reviewers at the call site
     */
    public static RequestPostProcessor offContract(String reason) {
        Assert.hasText(reason, "An off-contract request needs a reason");
        return request -> {
            request.setAttribute(OFF_CONTRACT_REASON, reason);
            return request;
        };
    }

    private static void assertMatchesContract(MvcResult result) {
        if (result.getRequest().getAttribute(OFF_CONTRACT_REASON) != null) {
            return;
        }
        List<String> violations = ApiContract.get().violations(result);
        if (!violations.isEmpty()) {
            throw new AssertionError("Response of " + result.getRequest().getMethod() + " "
                    + result.getRequest().getRequestURI() + " (status " + result.getResponse().getStatus()
                    + ") does not match " + ApiContract.CONTRACT_PATH.toString().replace('\\', '/') + ":\n  - "
                    + String.join("\n  - ", violations)
                    + "\nFix the response or update the contract first. A request that deliberately leaves"
                    + " the contract can be marked with ContractValidation.offContract(reason).");
        }
    }
}
