package io.quarkiverse.work.runtime.model;

import java.time.Instant;
import java.util.List;

import io.quarkiverse.work.runtime.api.WorkItemLabelResponse;

/**
 * Immutable request object used to create a new {@link WorkItem}.
 * All fields are optional except {@code title}; the service layer
 * applies defaults (expiry, claim deadline) for any null deadline fields.
 *
 * @param title Short human-readable title for the work item (required).
 * @param description Detailed description of what needs to be done.
 * @param category Logical category or process classification (e.g. "approval", "review").
 * @param formKey Key identifying the UI form to render for this work item.
 * @param priority Priority level; defaults to {@link WorkItemPriority#NORMAL} when null.
 * @param assigneeId Identity of the actor to whom the item is pre-assigned at creation.
 * @param candidateGroups Comma-separated group identifiers eligible to claim this item.
 * @param candidateUsers Comma-separated user identifiers eligible to claim this item.
 * @param requiredCapabilities Comma-separated capability tags the assignee must possess.
 * @param createdBy Identity of the actor or system that created the work item.
 * @param payload Arbitrary JSON payload carrying business context for the work item.
 * @param claimDeadline Absolute instant by which the item must be claimed; null uses default.
 * @param expiresAt Absolute instant by which the item must be completed; null uses default.
 * @param followUpDate Optional follow-up reminder date for inbox filtering.
 * @param labels Optional list of {@link WorkItemLabelResponse} labels to attach at creation; only MANUAL labels accepted.
 * @param confidenceScore Confidence score from the AI agent that created this WorkItem (0.0–1.0); null if not AI-created.
 * @param callerRef Opaque caller-supplied routing key set at spawn time; null for WorkItems not created via spawn.
 * @param claimDeadlineBusinessHours Claim deadline expressed in business hours; resolved to absolute {@code claimDeadline}
 *        via {@code BusinessCalendar} at create time. Takes precedence over {@code claimDeadline} when set.
 * @param expiresAtBusinessHours Completion deadline expressed in business hours; resolved to absolute {@code expiresAt}
 *        via {@code BusinessCalendar} at create time. Takes precedence over {@code expiresAt} when set.
 */
public record WorkItemCreateRequest(
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
