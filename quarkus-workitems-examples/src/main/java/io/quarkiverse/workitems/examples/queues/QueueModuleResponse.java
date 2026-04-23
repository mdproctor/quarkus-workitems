package io.quarkiverse.workitems.examples.queues;

import java.util.List;
import java.util.UUID;

import io.quarkiverse.workitems.examples.StepLog;
import io.quarkiverse.workitems.runtime.api.AuditEntryResponse;

/**
 * Response returned by the queue module scenario.
 *
 * @param scenario identifier of the scenario
 * @param steps chronological log of each step taken
 * @param queueId UUID of the queue view created for the contract-review pattern
 * @param queueSize number of WorkItems matching the queue pattern initially (expect &gt;= 2)
 * @param pickedUpWorkItemId UUID of the WorkItem that was picked up and transferred
 * @param finalAssignee the workerId who ended up owning the WorkItem after relinquishable pickup
 * @param auditTrail all audit entries for the picked-up WorkItem
 */
public record QueueModuleResponse(
        String scenario,
        List<StepLog> steps,
        UUID queueId,
        int queueSize,
        UUID pickedUpWorkItemId,
        String finalAssignee,
        List<AuditEntryResponse> auditTrail) {
}
