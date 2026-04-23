package io.quarkiverse.workitems.examples.filterrules;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

@QuarkusTest
class FilterRulesScenarioTest {

    @Test
    void run_filterRules_autoLabelsHighPriorityWorkItems() {
        final Response response = given()
                .contentType(ContentType.JSON)
                .when()
                .post("/examples/filterrules/run")
                .then()
                .statusCode(200)
                .extract().response();

        // Scenario identity
        assertThat(response.jsonPath().getString("scenario")).isEqualTo("dynamic-filter-rules");

        // All 5 steps logged
        final List<Map<String, Object>> steps = response.jsonPath().getList("steps");
        assertThat(steps).hasSize(5);
        assertThat(steps.get(0).get("description").toString()).contains("dynamic filter rule");
        assertThat(steps.get(4).get("description").toString()).contains("deletes");

        // WorkItem IDs present
        assertThat(response.jsonPath().getString("highPriorityWorkItemId")).isNotNull();
        assertThat(response.jsonPath().getString("normalPriorityWorkItemId")).isNotNull();

        // Core assertions: the filter applied "urgent" to the HIGH-priority item only
        assertThat(response.jsonPath().getBoolean("urgentLabelOnHighPriority"))
                .as("urgentLabelOnHighPriority should be true")
                .isTrue();
        assertThat(response.jsonPath().getBoolean("noUrgentLabelOnNormalPriority"))
                .as("noUrgentLabelOnNormalPriority should be true")
                .isTrue();

        // Audit trail present for both WorkItems
        final List<Map<String, Object>> audit = response.jsonPath().getList("auditTrail");
        assertThat(audit).hasSizeGreaterThanOrEqualTo(2);
        assertThat(audit.stream().anyMatch(e -> "CREATED".equals(e.get("event")))).isTrue();
    }
}
