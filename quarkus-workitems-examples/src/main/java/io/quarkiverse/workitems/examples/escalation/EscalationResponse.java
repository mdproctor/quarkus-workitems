package io.quarkiverse.workitems.examples.escalation;

import java.util.List;
import java.util.UUID;

import io.quarkiverse.workitems.examples.StepLog;
import io.quarkiverse.workitems.runtime.api.AuditEntryResponse;

/**
 * Response returned by the expiry-escalation scenario.
 *
 * @param scenario identifier of the scenario
 * @param steps chronological log of each step taken
 * @param workItemId UUID of the incident WorkItem created during the scenario
 * @param finalStatus status of the WorkItem after the expiry cleanup job ran
 * @param escalationEventPresent {@code true} if an {@code EXPIRED} audit event was found
 * @param auditTrail all audit entries for the WorkItem
 */
public record EscalationResponse(
        String scenario,
        List<StepLog> steps,
        UUID workItemId,
        String finalStatus,
        boolean escalationEventPresent,
        List<AuditEntryResponse> auditTrail) {
}
