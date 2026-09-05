package io.casehub.work.examples.resilience;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

@QuarkusTest
class CompensationResilienceScenarioTest {

    @Test
    void run_compensationResilience_allGuardsAndLifecycle() {
        final Response response = given()
                .contentType(ContentType.JSON)
                .when()
                .post("/examples/compensation-resilience/run")
                .then()
                .statusCode(200)
                .extract().response();

        assertThat(response.jsonPath().getString("scenario")).isEqualTo("compensation-resilience");

        assertThat(response.jsonPath().getBoolean("nonCompletedGuard.guardTriggered")).isTrue();
        assertThat(response.jsonPath().getString("nonCompletedGuard.errorMessage"))
                .contains("Only COMPLETED");

        assertThat(response.jsonPath().getBoolean("doubleCompensationGuard.guardTriggered")).isTrue();
        assertThat(response.jsonPath().getString("doubleCompensationGuard.errorMessage"))
                .contains("already has compensation activity");

        assertThat(response.jsonPath().getBoolean("compensatorGuard.guardTriggered")).isTrue();
        assertThat(response.jsonPath().getString("compensatorGuard.errorMessage"))
                .contains("cannot themselves be compensated");

        assertThat(response.jsonPath().getString("suspendResumeLifecycle.finalCompensationStatus"))
                .isEqualTo("COMPENSATED");
        final List<String> statuses = response.jsonPath().getList("suspendResumeLifecycle.compensatingStatuses");
        assertThat(statuses).contains("SUSPENDED");

        final List<Map<String, Object>> steps = response.jsonPath().getList("steps");
        assertThat(steps).hasSizeGreaterThanOrEqualTo(10);
    }
}
