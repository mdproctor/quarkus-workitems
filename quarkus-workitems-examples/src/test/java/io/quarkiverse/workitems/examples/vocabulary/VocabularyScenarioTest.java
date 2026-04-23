package io.quarkiverse.workitems.examples.vocabulary;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

@QuarkusTest
class VocabularyScenarioTest {

    @Test
    void run_hrVocabulary_registersEntriesAndApprovesLeave() {
        final Response response = given()
                .contentType(ContentType.JSON)
                .when()
                .post("/examples/vocabulary/run")
                .then()
                .statusCode(200)
                .extract().response();

        // Scenario identity
        assertThat(response.jsonPath().getString("scenario")).isEqualTo("hr-vocabulary");

        // Three vocabulary entries registered by the scenario
        final int registered = response.jsonPath().getInt("vocabularyEntriesRegistered");
        assertThat(registered).isGreaterThanOrEqualTo(3);

        // Annual leave WorkItem ID is present
        assertThat(response.jsonPath().getString("annualLeaveWorkItemId")).isNotNull();

        // Steps logged
        final List<Map<String, Object>> steps = response.jsonPath().getList("steps");
        assertThat(steps).hasSize(4);

        // Audit trail for the annual leave WorkItem includes CREATED and COMPLETED
        final List<Map<String, Object>> audit = response.jsonPath().getList("auditTrail");
        assertThat(audit).isNotEmpty();
        assertThat(audit.stream().anyMatch(e -> "CREATED".equals(e.get("event")))).isTrue();
        assertThat(audit.stream().anyMatch(e -> "COMPLETED".equals(e.get("event")))).isTrue();
    }
}
