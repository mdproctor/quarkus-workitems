package io.quarkiverse.workitems.examples.cancel;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

@QuarkusTest
class CancelScenarioTest {

    @Test
    void run_licenceCancel_workItemIsCancelled() {
        final Response response = given()
                .contentType(ContentType.JSON)
                .when()
                .post("/examples/cancel/run")
                .then()
                .statusCode(200)
                .extract().response();

        // Scenario identity
        assertThat(response.jsonPath().getString("scenario")).isEqualTo("licence-cancel");

        // Cancellation details
        assertThat(response.jsonPath().getString("cancelledBy")).isEqualTo("it-manager");
        assertThat(response.jsonPath().getString("finalStatus")).isEqualTo("CANCELLED");
        assertThat(response.jsonPath().getString("reason"))
                .contains("EL-2026-047");

        // WorkItem ID present
        assertThat(response.jsonPath().getString("workItemId")).isNotNull();

        // Steps logged
        final List<Map<String, Object>> steps = response.jsonPath().getList("steps");
        assertThat(steps).hasSize(3);

        // Audit trail contains CANCELLED event by it-manager
        final List<Map<String, Object>> audit = response.jsonPath().getList("auditTrail");
        assertThat(audit).isNotEmpty();
        assertThat(audit.stream().anyMatch(e -> "CANCELLED".equals(e.get("event")))).isTrue();
        assertThat(audit.stream()
                .filter(e -> "CANCELLED".equals(e.get("event")))
                .anyMatch(e -> "it-manager".equals(e.get("actor")))).isTrue();
    }
}
