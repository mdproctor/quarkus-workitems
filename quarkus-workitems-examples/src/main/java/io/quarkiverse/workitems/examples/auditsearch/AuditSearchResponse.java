package io.quarkiverse.workitems.examples.auditsearch;

import java.util.List;
import java.util.UUID;

import io.quarkiverse.workitems.examples.StepLog;

/**
 * Response returned by the cross-WorkItem audit search scenario.
 *
 * @param scenario identifier of the scenario
 * @param steps chronological log of each step taken
 * @param workItemIds UUIDs of all WorkItems created during the scenario
 * @param sarahActionCount total audit entries recorded for actor {@code auditor-sarah}
 * @param completionEventCount total {@code COMPLETED} events across all WorkItems
 * @param procurementAuditCount total audit entries for the {@code procurement} category
 */
public record AuditSearchResponse(
        String scenario,
        List<StepLog> steps,
        List<UUID> workItemIds,
        long sarahActionCount,
        long completionEventCount,
        long procurementAuditCount) {
}
