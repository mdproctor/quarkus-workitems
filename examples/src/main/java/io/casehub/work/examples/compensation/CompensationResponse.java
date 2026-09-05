package io.casehub.work.examples.compensation;

import java.util.List;
import java.util.UUID;

import io.casehub.work.examples.StepLog;
import io.casehub.work.api.AuditEntryResponse;

public record CompensationResponse(
        String scenario,
        List<StepLog> steps,
        UUID originalWorkItemId,
        UUID compensatingWorkItemId,
        String compensationStatus,
        String compensatingLink,
        String triggeredBy,
        String reason,
        List<AuditEntryResponse> originalAuditTrail,
        List<AuditEntryResponse> compensatingAuditTrail) {
}
