package io.quarkiverse.workitems.examples.vocabulary;

import java.util.List;
import java.util.UUID;

import io.quarkiverse.workitems.examples.StepLog;
import io.quarkiverse.workitems.runtime.api.AuditEntryResponse;

/**
 * Response returned by the HR vocabulary registration scenario.
 *
 * @param scenario identifier of the scenario that was run
 * @param steps chronological log of each step taken
 * @param vocabularyEntriesRegistered count of vocabulary entries registered by the scenario
 * @param annualLeaveWorkItemId UUID of the annual leave WorkItem approved in the scenario
 * @param auditTrail audit entries for the annual leave WorkItem
 */
public record VocabularyResponse(
        String scenario,
        List<StepLog> steps,
        int vocabularyEntriesRegistered,
        UUID annualLeaveWorkItemId,
        List<AuditEntryResponse> auditTrail) {
}
