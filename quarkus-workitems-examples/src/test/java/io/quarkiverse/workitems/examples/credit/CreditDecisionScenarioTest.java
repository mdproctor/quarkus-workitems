package io.quarkiverse.workitems.examples.credit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

@QuarkusTest
class CreditDecisionScenarioTest {

    @Test
    @SuppressWarnings("unchecked")
    void run_creditDecision_demonstratesComplianceFeatures() {
        final Response response = given()
                .contentType(ContentType.JSON)
                .when()
                .post("/examples/credit/run")
                .then()
                .statusCode(200)
                .extract().response();

        assertThat(response.jsonPath().getString("scenario")).isEqualTo("credit-decision");

        final List<Map<String, Object>> ledger = response.jsonPath().getList("ledgerEntries");

        // 9 transitions
        assertThat(ledger).hasSize(9);

        // Entry 1: provenance set
        final Map<String, Object> creationEntry = ledger.get(0);
        assertThat(creationEntry.get("commandType")).isEqualTo("CreateWorkItem");
        assertThat(creationEntry.get("sourceEntityId")).isEqualTo("LOAN-8821");
        assertThat(creationEntry.get("sourceEntityType")).isEqualTo("LoanApplication");
        assertThat(creationEntry.get("sourceEntitySystem")).isEqualTo("credit-engine");

        // Suspend entry present
        final Map<String, Object> suspendEntry = ledger.stream()
                .filter(e -> "WorkItemSuspended".equals(e.get("eventType")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No suspend entry found"));
        assertThat(suspendEntry.get("actorId")).isEqualTo("officer-alice");

        // Resume entry present
        assertThat(ledger.stream().anyMatch(e -> "WorkItemResumed".equals(e.get("eventType"))))
                .as("Expected a resume ledger entry").isTrue();

        // Delegation entry: causedByEntryId is non-null
        final Map<String, Object> delegateEntry = ledger.stream()
                .filter(e -> "WorkItemDelegated".equals(e.get("eventType")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No delegation entry found"));
        assertThat(delegateEntry.get("actorId")).isEqualTo("officer-alice");
        // causedByEntryId deferred — ObservabilitySupplement not yet in quarkus-ledger

        // Completion entry: rationale and planRef
        final Map<String, Object> completionEntry = ledger.stream()
                .filter(e -> "WorkItemCompleted".equals(e.get("eventType")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No completion entry found"));
        assertThat(completionEntry.get("actorId")).isEqualTo("supervisor-bob");
        assertThat(completionEntry.get("rationale")).isEqualTo("Income verified against payslips");
        assertThat(completionEntry.get("planRef")).isEqualTo("credit-policy-v2.1");

        // Attestation on completion entry
        final List<Map<String, Object>> attestations = (List<Map<String, Object>>) completionEntry.get("attestations");
        assertThat(attestations).hasSize(1);
        assertThat(attestations.get(0).get("attestorId")).isEqualTo("compliance-carol");
        assertThat(attestations.get(0).get("verdict")).isEqualTo("SOUND");

        // Hash chain intact across all 9 entries
        for (int i = 1; i < ledger.size(); i++) {
            final String prevDigest = ledger.get(i - 1).get("digest").toString();
            final String prevHash = ledger.get(i).get("previousHash").toString();
            assertThat(prevHash)
                    .as("Hash chain broken at entry %d", i + 1)
                    .isEqualTo(prevDigest);
        }

        // All 9 entries have decisionContext
        ledger.forEach(entry -> assertThat(entry.get("decisionContext"))
                .as("decisionContext missing on entry seq=%s", entry.get("sequenceNumber"))
                .isNotNull());
    }
}
