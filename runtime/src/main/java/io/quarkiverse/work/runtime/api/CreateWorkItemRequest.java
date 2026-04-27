package io.quarkiverse.work.runtime.api;

import java.time.Instant;
import java.util.List;

import io.quarkiverse.work.runtime.model.WorkItemPriority;

public record CreateWorkItemRequest(
        String title,
        String description,
        String category,
        String formKey,
        WorkItemPriority priority,
        String assigneeId,
        String candidateGroups,
        String candidateUsers,
        String requiredCapabilities,
        String createdBy,
        String payload,
        Instant claimDeadline,
        Instant expiresAt,
        Instant followUpDate,
        List<WorkItemLabelResponse> labels,
        Double confidenceScore,
        String callerRef,
        Integer claimDeadlineBusinessHours,
        Integer expiresAtBusinessHours) {
}
