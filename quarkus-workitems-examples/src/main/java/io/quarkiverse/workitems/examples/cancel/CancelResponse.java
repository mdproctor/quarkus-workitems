package io.quarkiverse.workitems.examples.cancel;

import java.util.List;
import java.util.UUID;

import io.quarkiverse.workitems.examples.StepLog;
import io.quarkiverse.workitems.runtime.api.AuditEntryResponse;

/**
 * Response returned by the software licence cancellation scenario.
 *
 * @param scenario identifier of the scenario that was run
 * @param steps chronological log of each step taken
 * @param workItemId the UUID of the WorkItem that was cancelled
 * @param cancelledBy the actor who performed the cancellation
 * @param reason the reason given for the cancellation
 * @param finalStatus the status of the WorkItem after cancellation
 * @param auditTrail all audit entries for the WorkItem, in occurrence order
 */
public record CancelResponse(
        String scenario,
        List<StepLog> steps,
        UUID workItemId,
        String cancelledBy,
        String reason,
        String finalStatus,
        List<AuditEntryResponse> auditTrail) {
}
