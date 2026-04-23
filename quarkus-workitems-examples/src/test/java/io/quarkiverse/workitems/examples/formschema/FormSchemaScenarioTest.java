package io.quarkiverse.workitems.examples.formschema;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

@QuarkusTest
class FormSchemaScenarioTest {

    @Test
    void run_formSchema_registersSchemaAndDrivesWorkItemLifecycle() {
        final Response response = given()
                .contentType(ContentType.JSON)
                .when()
                .post("/examples/formschema/run")
                .then()
                .statusCode(200)
                .extract().response();

        // Scenario identity
        assertThat(response.jsonPath().getString("scenario")).isEqualTo("form-schema");

        // Steps logged (6 steps: register, list, get by id, create WorkItem, complete, delete)
        final List<Map<String, Object>> steps = response.jsonPath().getList("steps");
        assertThat(steps).hasSize(6);
        assertThat(steps.get(0).get("description").toString()).containsIgnoringCase("registers");
        assertThat(steps.get(5).get("description").toString()).containsIgnoringCase("delete");

        // Schema ID and name present
        assertThat(response.jsonPath().getString("schemaId")).isNotNull();
        assertThat(response.jsonPath().getString("schemaName")).isEqualTo("Contract Review Form");

        // WorkItem was created
        assertThat(response.jsonPath().getString("workItemId")).isNotNull();

        // Schema was deleted after the scenario ran
        assertThat(response.jsonPath().getBoolean("schemaDeletedAfterRun")).isTrue();

        // Audit trail: at least CREATED, STARTED, COMPLETED
        final List<Map<String, Object>> audit = response.jsonPath().getList("auditTrail");
        assertThat(audit).isNotEmpty();
        assertThat(audit.stream().anyMatch(e -> "CREATED".equals(e.get("event")))).isTrue();
        assertThat(audit.stream().anyMatch(e -> "STARTED".equals(e.get("event")))).isTrue();
        assertThat(audit.stream().anyMatch(e -> "COMPLETED".equals(e.get("event")))).isTrue();
    }
}
