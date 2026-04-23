package io.quarkiverse.workitems.examples.escalation;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

@QuarkusTest
class EscalationScenarioTest {

    @Test
    void run_escalation_expiresWorkItemAndRecordsAuditEvent() {
        final Response response = given()
                .contentType(ContentType.JSON)
                .when()
                .post("/examples/escalation/run")
                .then()
                .statusCode(200)
                .extract().response();

        // Scenario identity
        assertThat(response.jsonPath().getString("scenario")).isEqualTo("expiry-escalation");

        // Steps logged (4 steps: create, trigger cleanup, reload, collect audit)
        final List<Map<String, Object>> steps = response.jsonPath().getList("steps");
        assertThat(steps).hasSize(4);
        assertThat(steps.get(0).get("description").toString()).containsIgnoringCase("creates");
        assertThat(steps.get(1).get("description").toString()).containsIgnoringCase("checkExpired");

        // WorkItem ID present
        assertThat(response.jsonPath().getString("workItemId")).isNotNull();

        // WorkItem status is EXPIRED after cleanup job ran
        assertThat(response.jsonPath().getString("finalStatus")).isEqualTo("EXPIRED");

        // Escalation event is present in audit trail
        assertThat(response.jsonPath().getBoolean("escalationEventPresent")).isTrue();

        // Audit trail contains both CREATED and EXPIRED events
        final List<Map<String, Object>> audit = response.jsonPath().getList("auditTrail");
        assertThat(audit).isNotEmpty();
        assertThat(audit.stream().anyMatch(e -> "CREATED".equals(e.get("event")))).isTrue();
        assertThat(audit.stream().anyMatch(e -> "EXPIRED".equals(e.get("event")))).isTrue();
    }
}
