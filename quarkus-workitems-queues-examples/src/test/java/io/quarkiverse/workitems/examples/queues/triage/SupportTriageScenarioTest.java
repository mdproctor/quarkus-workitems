package io.quarkiverse.workitems.examples.queues.triage;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class SupportTriageScenarioTest {

    @Test
    void supportTriage_criticalTicket_getsFastTrackLabel() {
        given()
                .post("/queue-examples/triage/run")
                .then()
                .statusCode(200)
                .body("scenarioId", equalTo("support-triage-cascade"))
                // Step 1: CRITICAL ticket has sla/critical + queue/fast-track
                .body("steps[0].inferredLabels", hasItems("sla/critical", "queue/fast-track"))
                // Step 1: CRITICAL ticket does NOT get intake/triage (condition is HIGH only)
                .body("steps[0].inferredLabels", not(hasItem("intake/triage")))
                // Step 2: HIGH ticket gets intake/triage AND team/support-lead (cascade)
                .body("steps[1].inferredLabels", hasItems("intake/triage", "team/support-lead"))
                // Step 3: after claim, HIGH ticket loses intake labels
                .body("steps[2].inferredLabels", not(hasItem("intake/triage")))
                .body("steps[2].inferredLabels", not(hasItem("team/support-lead")))
                // Step 4: fast-track queue has content
                .body("queueContents", hasSize(greaterThanOrEqualTo(1)));
    }
}
