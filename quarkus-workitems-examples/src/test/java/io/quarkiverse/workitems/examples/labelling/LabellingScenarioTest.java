package io.quarkiverse.workitems.examples.labelling;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

@QuarkusTest
class LabellingScenarioTest {

    @Test
    void run_labelManagement_labelsAppliedAndQueried() {
        final Response response = given()
                .contentType(ContentType.JSON)
                .when()
                .post("/examples/labelling/run")
                .then()
                .statusCode(200)
                .extract().response();

        // Scenario identity
        assertThat(response.jsonPath().getString("scenario")).isEqualTo("label-management");

        // WorkItem ID present
        assertThat(response.jsonPath().getString("workItemId")).isNotNull();

        // Steps logged
        final List<Map<String, Object>> steps = response.jsonPath().getList("steps");
        assertThat(steps).hasSize(6);

        // Labels at completion include both "priority/high" and "customer/vip"
        final List<String> labels = response.jsonPath().getList("labelsAtCompletion");
        assertThat(labels).contains("priority/high", "customer/vip");

        // The customer/* label pattern matched at least this WorkItem
        final int matchCount = response.jsonPath().getInt("itemsMatchingCustomerLabel");
        assertThat(matchCount).isGreaterThanOrEqualTo(1);

        // Audit trail is not empty
        final List<Map<String, Object>> audit = response.jsonPath().getList("auditTrail");
        assertThat(audit).isNotEmpty();
        assertThat(audit.stream().anyMatch(e -> "CREATED".equals(e.get("event")))).isTrue();
        assertThat(audit.stream().anyMatch(e -> "COMPLETED".equals(e.get("event")))).isTrue();
    }
}
