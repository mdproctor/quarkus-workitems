package io.quarkiverse.workitems.examples.queues.finance;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class FinanceApprovalScenarioTest {

    @Test
    void financeApproval_criticalSpend_getsExecReview() {
        given()
                .post("/queue-examples/finance/run")
                .then()
                .statusCode(200)
                .body("scenarioId", equalTo("finance-approval-chain"))
                // Step 1: NORMAL → standard approval only
                .body("steps[0].inferredLabels", hasItem("finance/approval"))
                .body("steps[0].inferredLabels", not(hasItem("finance/exec-review")))
                // Step 2: HIGH → standard approval only (CRITICAL threshold not met)
                .body("steps[1].inferredLabels", hasItem("finance/approval"))
                .body("steps[1].inferredLabels", not(hasItem("finance/exec-review")))
                // Step 3: CRITICAL → both queues
                .body("steps[2].inferredLabels", hasItems("finance/approval", "finance/exec-review"))
                // Exec queue has CRITICAL item only
                .body("queueContents", hasSize(greaterThanOrEqualTo(1)));
    }
}
