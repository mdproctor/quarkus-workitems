package io.quarkiverse.workitems.examples.lowconfidence;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

@QuarkusTest
class LowConfidenceScenarioTest {

    @Test
    void run_lowConfidence_flagsUncertainAiWorkItems() {
        final Response response = given()
                .contentType(ContentType.JSON)
                .when()
                .post("/examples/lowconfidence/run")
                .then()
                .statusCode(200)
                .extract().response();

        // Scenario identity
        assertThat(response.jsonPath().getString("scenario")).isEqualTo("low-confidence-ai-filter");

        // All 5 steps logged
        final List<Map<String, Object>> steps = response.jsonPath().getList("steps");
        assertThat(steps).hasSize(5);
        assertThat(steps.get(0).get("description").toString()).contains("0.95");
        assertThat(steps.get(1).get("description").toString()).contains("0.45");
        assertThat(steps.get(2).get("description").toString()).isNotEmpty();

        // WorkItem IDs present
        assertThat(response.jsonPath().getString("highConfidenceWorkItemId")).isNotNull();
        assertThat(response.jsonPath().getString("lowConfidenceWorkItemId")).isNotNull();
        assertThat(response.jsonPath().getString("nullConfidenceWorkItemId")).isNotNull();

        // Core assertions: only the low-confidence item is flagged
        assertThat(response.jsonPath().getBoolean("highConfidenceClean"))
                .as("highConfidenceClean should be true — no ai/low-confidence label")
                .isTrue();
        assertThat(response.jsonPath().getBoolean("lowConfidenceFlagged"))
                .as("lowConfidenceFlagged should be true — ai/low-confidence label present")
                .isTrue();
        assertThat(response.jsonPath().getBoolean("nullConfidenceClean"))
                .as("nullConfidenceClean should be true — null score does not trigger the filter")
                .isTrue();

        // Audit trail present across all three WorkItems
        final List<Map<String, Object>> audit = response.jsonPath().getList("auditTrail");
        assertThat(audit).hasSizeGreaterThanOrEqualTo(3);
        assertThat(audit.stream().anyMatch(e -> "CREATED".equals(e.get("event")))).isTrue();
        assertThat(audit.stream().anyMatch(e -> "COMPLETED".equals(e.get("event")))).isTrue();
    }
}
