package io.quarkiverse.work.ai.escalation;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.work.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.work.runtime.model.WorkItemPriority;
import io.quarkiverse.work.runtime.service.ExpiryCleanupJob;
import io.quarkiverse.work.runtime.service.WorkItemService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

/**
 * Integration tests for {@link EscalationSummaryResource} and the full observer flow.
 *
 * <p>
 * No {@code ChatModel} is configured in test profile, so summaries have {@code null} text.
 * This exercises the graceful-degradation path and verifies the observer fires and persists.
 */
@QuarkusTest
class EscalationSummaryResourceTest {

    @Inject
    WorkItemService workItemService;

    @Inject
    ExpiryCleanupJob expiryCleanupJob;

    @BeforeEach
    @Transactional
    void cleanup() {
        EscalationSummary.deleteAll();
    }

    @Test
    void list_noEscalations_returnsEmptyList() {
        final Response create = given()
                .contentType("application/json")
                .body("{\"title\":\"Review contract\",\"category\":\"legal\",\"createdBy\":\"system\"}")
                .when().post("/workitems")
                .then().statusCode(201)
                .extract().response();

        final String id = create.jsonPath().getString("id");

        final Response list = given()
                .when().get("/workitems/" + id + "/escalation-summaries")
                .then().statusCode(200)
                .extract().response();

        assertThat(list.jsonPath().getList("$")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_afterExpiry_summaryPersistedWithNullText() {
        // Create WorkItem with past expiresAt so ExpiryCleanupJob fires on it
        final WorkItemCreateRequest req = new WorkItemCreateRequest(
                "Approve expense claim",
                "Team offsite expenses Q2",
                "finance",
                null,
                WorkItemPriority.NORMAL,
                null, null, null, null,
                "test-system",
                null,
                null,
                Instant.now().minusSeconds(10), // already expired
                null, null, null, null, null, null);

        final var wi = workItemService.create(req);

        // Trigger the expiry job — fires EXPIRED event → observer → summary
        expiryCleanupJob.checkExpired();

        // Allow the AFTER_SUCCESS observer transaction to commit by calling GET
        final Response list = given()
                .when().get("/workitems/" + wi.id + "/escalation-summaries")
                .then().statusCode(200)
                .extract().response();

        final List<Map<String, Object>> summaries = list.jsonPath().getList("$");
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).get("workItemId")).isEqualTo(wi.id.toString());
        assertThat(summaries.get(0).get("eventType")).isEqualTo("EXPIRED");
        // No ChatModel configured — summary text is null
        assertThat(summaries.get(0).get("summary")).isNull();
        assertThat(summaries.get(0).get("generatedAt")).isNotNull();
    }
}
