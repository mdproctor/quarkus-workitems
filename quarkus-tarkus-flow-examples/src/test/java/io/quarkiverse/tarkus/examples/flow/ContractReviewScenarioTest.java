package io.quarkiverse.tarkus.examples.flow;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

/**
 * End-to-end test for the contract review workflow scenario.
 *
 * <p>
 * Verifies that {@code POST /examples/flow/run} drives the full {@link ContractReviewWorkflow}
 * through both human WorkItem steps and returns the final execution result.
 * The scenario runner handles all the WorkItem lifecycle calls internally;
 * this test only inspects the JSON response.
 */
@QuarkusTest
class ContractReviewScenarioTest {

    @Test
    void run_contractReview_drivesFullWorkflowThroughBothHumanSteps() {
        final Response response = given()
                .contentType(ContentType.JSON)
                .when()
                .post("/examples/flow/run")
                .then()
                .statusCode(200)
                .extract().response();

        // Scenario identity
        assertThat(response.jsonPath().getString("scenario")).isEqualTo("contract-review");

        // Both workItem() steps created WorkItems
        final List<String> workItemIds = response.jsonPath().getList("workItemIds");
        assertThat(workItemIds)
                .as("Expected 2 WorkItems: one for legal review, one for executive sign-off")
                .hasSize(2);

        // Steps log covers all 4 workflow steps
        final List<String> steps = response.jsonPath().getList("steps");
        assertThat(steps).hasSizeGreaterThanOrEqualTo(4);

        // Step descriptions identify each transition
        assertThat(steps.stream().anyMatch(s -> s.contains("validate"))).isTrue();
        assertThat(steps.stream().anyMatch(s -> s.contains("legalReview"))).isTrue();
        assertThat(steps.stream().anyMatch(s -> s.contains("executiveSignOff"))).isTrue();
        assertThat(steps.stream().anyMatch(s -> s.contains("countersign"))).isTrue();

        // Workflow ran to completion
        final String finalResult = response.jsonPath().getString("finalResult");
        assertThat(finalResult)
                .as("Workflow should produce an EXECUTED result after both human approvals")
                .isNotNull()
                .isNotBlank();
    }

    @Test
    @SuppressWarnings("unchecked")
    void run_contractReview_workItemIdsAreDistinct() {
        final Response response = given()
                .contentType(ContentType.JSON)
                .when()
                .post("/examples/flow/run")
                .then()
                .statusCode(200)
                .extract().response();

        final List<String> workItemIds = response.jsonPath().getList("workItemIds");
        assertThat(workItemIds).hasSize(2);
        assertThat(workItemIds.get(0))
                .as("Legal review and exec sign-off must be different WorkItems")
                .isNotEqualTo(workItemIds.get(1));
    }
}
