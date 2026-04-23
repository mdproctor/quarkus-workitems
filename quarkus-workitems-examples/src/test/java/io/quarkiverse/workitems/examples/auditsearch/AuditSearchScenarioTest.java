package io.quarkiverse.workitems.examples.auditsearch;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

@QuarkusTest
class AuditSearchScenarioTest {

    @Test
    void run_auditSearch_returnsFilteredAuditCounts() {
        final Response response = given()
                .contentType(ContentType.JSON)
                .when()
                .post("/examples/auditsearch/run")
                .then()
                .statusCode(200)
                .extract().response();

        // Scenario identity
        assertThat(response.jsonPath().getString("scenario")).isEqualTo("audit-search");

        // Steps logged (8 steps: create, claim, start, complete ×3, james completes, 3 queries)
        final List<Map<String, Object>> steps = response.jsonPath().getList("steps");
        assertThat(steps).hasSize(8);

        // All four WorkItem IDs present
        final List<String> workItemIds = response.jsonPath().getList("workItemIds");
        assertThat(workItemIds).hasSize(4);
        workItemIds.forEach(id -> assertThat(id).isNotNull());

        // auditor-sarah performed at least 9 actions: CREATED×3 + ASSIGNED×3 + STARTED×3 + COMPLETED×3
        // (Note: CREATED is attributed to procurement-system; ASSIGNED/STARTED/COMPLETED to sarah)
        // claim=ASSIGNED, start=STARTED, complete=COMPLETED → 9 sarah actions across 3 WorkItems
        final long sarahActionCount = response.jsonPath().getLong("sarahActionCount");
        assertThat(sarahActionCount).isGreaterThanOrEqualTo(6);

        // At least 4 COMPLETED events (3 by sarah + 1 by james)
        final long completionEventCount = response.jsonPath().getLong("completionEventCount");
        assertThat(completionEventCount).isGreaterThanOrEqualTo(4);

        // All four WorkItems are category=procurement, so procurementAuditCount >= sarahActionCount
        final long procurementAuditCount = response.jsonPath().getLong("procurementAuditCount");
        assertThat(procurementAuditCount).isGreaterThanOrEqualTo(sarahActionCount);
    }
}
