package io.quarkiverse.workitems.examples.queues;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

@QuarkusTest
class QueueModuleScenarioTest {

    @Test
    void run_queueModule_specialistPickupAndHandoff() {
        final Response response = given()
                .contentType(ContentType.JSON)
                .when()
                .post("/examples/queues/run")
                .then()
                .statusCode(200)
                .extract().response();

        // Scenario identity
        assertThat(response.jsonPath().getString("scenario")).isEqualTo("queue-module-specialist-queues");

        // All 9 steps logged
        final List<Map<String, Object>> steps = response.jsonPath().getList("steps");
        assertThat(steps).hasSize(9);
        assertThat(steps.get(0).get("description").toString()).contains("contract-review/nda");
        assertThat(steps.get(7).get("description").toString()).contains("relinquishable");

        // Queue view created
        assertThat(response.jsonPath().getString("queueId")).isNotNull();

        // Queue size: WorkItems A and C match contract-review/*; B (compliance/gdpr) does not
        final int queueSize = response.jsonPath().getInt("queueSize");
        assertThat(queueSize)
                .as("queue should contain at least WorkItems A and C (contract-review/* pattern)")
                .isGreaterThanOrEqualTo(2);

        // Picked-up WorkItem ID present
        assertThat(response.jsonPath().getString("pickedUpWorkItemId")).isNotNull();

        // Final assignee is the senior-specialist who took over via relinquishable pickup
        assertThat(response.jsonPath().getString("finalAssignee"))
                .as("finalAssignee should be senior-specialist after relinquishable handoff")
                .isEqualTo("senior-specialist");

        // Audit trail contains lifecycle events for WorkItem A
        final List<Map<String, Object>> audit = response.jsonPath().getList("auditTrail");
        assertThat(audit).isNotEmpty();
        assertThat(audit.stream().anyMatch(e -> "CREATED".equals(e.get("event")))).isTrue();
        assertThat(audit.stream().anyMatch(e -> "COMPLETED".equals(e.get("event")))).isTrue();
    }
}
