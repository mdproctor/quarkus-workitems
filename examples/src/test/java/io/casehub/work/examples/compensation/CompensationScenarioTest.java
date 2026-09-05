package io.casehub.work.examples.compensation;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

@QuarkusTest
class CompensationScenarioTest {

    @Test
    void run_expenseCompensation_originalIsCompensated() {
        final Response response = given()
                .contentType(ContentType.JSON)
                .when()
                .post("/examples/compensation/run")
                .then()
                .statusCode(200)
                .extract().response();

        assertThat(response.jsonPath().getString("scenario")).isEqualTo("expense-compensation");

        final String originalId = response.jsonPath().getString("originalWorkItemId");
        final String compensatingId = response.jsonPath().getString("compensatingWorkItemId");
        assertThat(originalId).isNotNull();
        assertThat(compensatingId).isNotNull();
        assertThat(originalId).isNotEqualTo(compensatingId);

        assertThat(response.jsonPath().getString("compensationStatus")).isEqualTo("COMPENSATED");
        assertThat(response.jsonPath().getString("compensatingLink")).isEqualTo(originalId);

        assertThat(response.jsonPath().getString("triggeredBy")).isEqualTo("internal-audit");
        assertThat(response.jsonPath().getString("reason")).contains("cancelled project");

        final List<Map<String, Object>> steps = response.jsonPath().getList("steps");
        assertThat(steps).hasSize(8);

        final List<Map<String, Object>> originalAudit = response.jsonPath().getList("originalAuditTrail");
        assertThat(originalAudit).isNotEmpty();
        assertThat(originalAudit.stream()
                .anyMatch(e -> "COMPENSATION_STARTED".equals(e.get("event")))).isTrue();
        assertThat(originalAudit.stream()
                .anyMatch(e -> "COMPENSATION_COMPLETED".equals(e.get("event")))).isTrue();

        final List<Map<String, Object>> compensatingAudit = response.jsonPath().getList("compensatingAuditTrail");
        assertThat(compensatingAudit).isNotEmpty();
    }
}
