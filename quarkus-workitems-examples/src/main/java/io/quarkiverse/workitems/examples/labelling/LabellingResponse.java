package io.quarkiverse.workitems.examples.labelling;

import java.util.List;
import java.util.UUID;

import io.quarkiverse.workitems.examples.StepLog;
import io.quarkiverse.workitems.runtime.api.AuditEntryResponse;

/**
 * Response returned by the label management scenario.
 *
 * @param scenario identifier of the scenario that was run
 * @param steps chronological log of each step taken
 * @param workItemId the UUID of the WorkItem driven through its lifecycle
 * @param labelsAtCompletion label paths present on the WorkItem just before removing "customer/vip"
 * @param itemsMatchingCustomerLabel count of WorkItems matching the "customer/*" label pattern at query time
 * @param auditTrail all audit entries for the WorkItem, in occurrence order
 */
public record LabellingResponse(
        String scenario,
        List<StepLog> steps,
        UUID workItemId,
        List<String> labelsAtCompletion,
        int itemsMatchingCustomerLabel,
        List<AuditEntryResponse> auditTrail) {
}
