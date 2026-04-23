package io.quarkiverse.workitems.examples.formschema;

import java.util.List;
import java.util.UUID;

import io.quarkiverse.workitems.examples.StepLog;
import io.quarkiverse.workitems.runtime.api.AuditEntryResponse;

/**
 * Response returned by the form schema registration scenario.
 *
 * @param scenario identifier of the scenario
 * @param steps chronological log of each step taken
 * @param schemaId UUID of the {@code WorkItemFormSchema} created during the scenario
 * @param schemaName display name of the registered schema
 * @param workItemId UUID of the WorkItem driven through its lifecycle with the schema
 * @param schemaDeletedAfterRun {@code true} if the schema was deleted at the end of the scenario
 * @param auditTrail all audit entries for the WorkItem
 */
public record FormSchemaResponse(
        String scenario,
        List<StepLog> steps,
        UUID schemaId,
        String schemaName,
        UUID workItemId,
        boolean schemaDeletedAfterRun,
        List<AuditEntryResponse> auditTrail) {
}
