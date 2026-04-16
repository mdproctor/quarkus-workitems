package io.quarkiverse.workitems.examples.queues.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class SecurityEscalationScenarioTest {

    @Test
    void security_criticalBreach_triggersExecEscalateCascade() {
        given()
                .post("/queue-examples/security/run")
                .then()
                .statusCode(200)
                .body("scenarioId", equalTo("security-exec-escalation"))
                // Step 1: HIGH incident — security/incident only
                .body("steps[0].inferredLabels", hasItem("security/incident"))
                .body("steps[0].inferredLabels", not(hasItem("priority/critical")))
                .body("steps[0].inferredLabels", not(hasItem("security/exec-escalate")))
                // Step 2: CRITICAL breach — all 3 labels via cascade
                .body("steps[1].inferredLabels", hasItems(
                        "security/incident",
                        "priority/critical",
                        "security/exec-escalate"))
                // Exec escalate queue has the CRITICAL item
                .body("queueContents", hasSize(greaterThanOrEqualTo(1)));
    }
}
