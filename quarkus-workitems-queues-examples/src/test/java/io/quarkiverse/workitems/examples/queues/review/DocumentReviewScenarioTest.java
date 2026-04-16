package io.quarkiverse.workitems.examples.queues.review;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class DocumentReviewScenarioTest {

    @Test
    void documentReview_lambdaOverridesNormalPriorityToUrgent() {
        given()
                .post("/queue-examples/review/run?delay=0")
                .then()
                .statusCode(200)
                .body("scenarioId", equalTo("document-review-pipeline"))

                // Step 1: Security advisory — priority=NORMAL but Lambda overrides to urgent
                .body("steps[0].inferredLabels", hasItems("review/urgent", "review/urgent/unassigned"))
                .body("steps[0].inferredLabels", not(hasItem("review/routine"))) // NOT in routine despite NORMAL priority

                // Step 2: Release notes — HIGH → standard tier
                .body("steps[1].inferredLabels", hasItems("review/standard", "review/standard/unassigned"))
                .body("steps[1].inferredLabels", not(hasItem("review/urgent")))

                // Step 3: Tutorial — NORMAL → routine tier
                .body("steps[2].inferredLabels", hasItems("review/routine", "review/routine/unassigned"))
                .body("steps[2].inferredLabels", not(hasItem("review/urgent")))

                // Step 4: After claim — unassigned removed, claimed applied; tier label stays
                .body("steps[3].inferredLabels", hasItem("review/urgent"))
                .body("steps[3].inferredLabels", hasItem("review/urgent/claimed"))
                .body("steps[3].inferredLabels", not(hasItem("review/urgent/unassigned")))

                // Step 5: After start — claimed removed, active applied
                .body("steps[4].inferredLabels", hasItem("review/urgent"))
                .body("steps[4].inferredLabels", hasItem("review/urgent/active"))
                .body("steps[4].inferredLabels", not(hasItem("review/urgent/claimed")))

                // Step 6: Queue snapshot — urgent/unassigned empty, active has 1
                .body("steps[5].inferredLabels", hasItem(containsString("review/urgent/unassigned: 0")))
                .body("steps[5].inferredLabels", hasItem(containsString("review/urgent/active: 1")))
                .body("steps[5].inferredLabels", hasItem(containsString("review/standard/unassigned: 1")))
                .body("steps[5].inferredLabels", hasItem(containsString("review/routine/unassigned: 1")))

                // Step 7: After complete — ALL inferred labels gone (COMPLETED is not PENDING/ASSIGNED/IN_PROGRESS)
                .body("steps[6].inferredLabels", empty());
    }
}
