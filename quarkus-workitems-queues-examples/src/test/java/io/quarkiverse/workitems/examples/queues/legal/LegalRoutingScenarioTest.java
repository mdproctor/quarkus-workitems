package io.quarkiverse.workitems.examples.queues.legal;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class LegalRoutingScenarioTest {

    @Test
    void legalRouting_normalItem_getsReviewOnly() {
        given()
                .post("/queue-examples/legal/run")
                .then()
                .statusCode(200)
                .body("scenarioId", equalTo("legal-compliance-routing"))
                // Step 1: NORMAL legal → review only
                .body("steps[0].inferredLabels", hasItem("legal/review"))
                .body("steps[0].inferredLabels", not(hasItem("legal/urgent")))
                // Step 2: HIGH legal → both review and urgent
                .body("steps[1].inferredLabels", hasItems("legal/review", "legal/urgent"))
                // Exec queue has HIGH item
                .body("queueContents", hasSize(greaterThanOrEqualTo(1)));
    }
}
