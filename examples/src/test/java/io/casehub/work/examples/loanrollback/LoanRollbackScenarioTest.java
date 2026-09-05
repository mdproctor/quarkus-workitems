package io.casehub.work.examples.loanrollback;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

@QuarkusTest
class LoanRollbackScenarioTest {

    @Test
    void run_loanRollback_allThreeCompensatedInReverseOrder() {
        final Response response = given()
                .contentType(ContentType.JSON)
                .when()
                .post("/examples/loan-rollback/run")
                .then()
                .statusCode(200)
                .extract().response();

        assertThat(response.jsonPath().getString("scenario")).isEqualTo("loan-rollback");

        final List<Map<String, Object>> forwardSteps = response.jsonPath().getList("forwardSteps");
        assertThat(forwardSteps).hasSize(3);

        final List<Map<String, Object>> compensationSteps = response.jsonPath().getList("compensationSteps");
        assertThat(compensationSteps).hasSize(3);

        assertThat(response.jsonPath().getString("compensationOrder"))
                .isEqualTo("approval → valuation → credit-check");

        for (final Map<String, Object> step : forwardSteps) {
            assertThat(step.get("compensationStatus")).isEqualTo("COMPENSATED");
        }

        for (final Map<String, Object> step : forwardSteps) {
            assertThat(step.get("compensatingId")).isNotNull();
        }

        final List<Map<String, Object>> steps = response.jsonPath().getList("steps");
        assertThat(steps).hasSizeGreaterThanOrEqualTo(14);
    }
}
