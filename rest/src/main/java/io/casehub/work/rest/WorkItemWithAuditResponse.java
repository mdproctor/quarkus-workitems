package io.casehub.work.rest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.casehub.work.api.DeclineTarget;
import io.casehub.work.api.Outcome;
import io.casehub.work.api.WorkItemPriority;
import io.casehub.work.api.CompensationStatus;
import io.casehub.work.api.WorkItemStatus;

public record WorkItemWithAuditResponse(
        UUID id,
        String title,
        String description,
        List<String> types,
        String formKey,
        WorkItemStatus status,
        WorkItemPriority priority,
        String assigneeId,
        String owner,
        String candidateGroups,
        String candidateUsers,
        String requiredCapabilities,
        String createdBy,
        DeclineTarget delegationDeclineTarget,
        String delegationChain,
        WorkItemStatus priorStatus,
        String payload,
        String resolution,
        Instant claimDeadline,
        Instant expiresAt,
        Instant followUpDate,
        Instant createdAt,
        Instant updatedAt,
        Instant assignedAt,
        Instant startedAt,
        Instant completedAt,
        Instant suspendedAt,
        List<WorkItemLabelResponse> labels,
        List<io.casehub.work.api.AuditEntryResponse> auditTrail,
        /**
         * Confidence score from the AI agent that created this WorkItem (0.0–1.0).
         * Null when created by a human or when no confidence metadata was provided.
         */
        Double confidenceScore,
        /**
         * Opaque caller-supplied routing key set at spawn time.
         * Null for WorkItems not created via spawn.
         */
        String callerRef,
        Long version,
        /** UUID of the template this item was instantiated from; null for direct creation. */
        UUID templateId,
        /** Version of the template used at instantiation; null for non-template WorkItems. */
        Long templateVersion,
        /** Named outcome recorded at completion; null until COMPLETED. */
        String outcome,
        /** Permitted outcome names snapshotted from the template; null means no constraint. */
        List<Outcome> permittedOutcomes,
        /** JSON Schema for payload; snapshotted from template at instantiation. Null if unconstrained. */
        String inputDataSchema,
        /** JSON Schema for resolution; snapshotted from template at instantiation. Null if unconstrained. */
        String outputDataSchema,
        String excludedUsers,
        String scope,
        String candidateScores,
        String routingExperiences,
        CompensationStatus compensationStatus,
        UUID compensatesWorkItemId) {
}
