package io.quarkiverse.workitems.examples.lowconfidence;

import java.util.List;
import java.util.UUID;

import io.quarkiverse.workitems.examples.StepLog;
import io.quarkiverse.workitems.runtime.api.AuditEntryResponse;

/**
 * Response returned by the low-confidence AI filter scenario.
 *
 * @param scenario identifier of the scenario
 * @param steps chronological log of each step taken
 * @param highConfidenceWorkItemId UUID of the WorkItem created with high confidence (0.95)
 * @param lowConfidenceWorkItemId UUID of the WorkItem created with low confidence (0.45)
 * @param nullConfidenceWorkItemId UUID of the WorkItem created with no confidence score (manual)
 * @param highConfidenceClean true if the high-confidence item does NOT have the ai/low-confidence label
 * @param lowConfidenceFlagged true if the low-confidence item HAS the ai/low-confidence label
 * @param nullConfidenceClean true if the null-confidence item does NOT have the ai/low-confidence label
 * @param auditTrail all audit entries across all three WorkItems
 */
public record LowConfidenceResponse(
        String scenario,
        List<StepLog> steps,
        UUID highConfidenceWorkItemId,
        UUID lowConfidenceWorkItemId,
        UUID nullConfidenceWorkItemId,
        boolean highConfidenceClean,
        boolean lowConfidenceFlagged,
        boolean nullConfidenceClean,
        List<AuditEntryResponse> auditTrail) {
}
