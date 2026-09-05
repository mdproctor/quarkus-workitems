package io.casehub.work.rest;

import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.runtime.model.AuditEntry;
import io.casehub.work.runtime.model.OutcomeCodecs;

import java.util.List;

public final class WorkItemMapper {

    private WorkItemMapper() {
    }

    public static WorkItemResponse toResponse(final io.casehub.work.api.WorkItem wi) {
        return toResponse(wi, entityVersion(wi.id()));
    }

    public static WorkItemResponse toResponse(final io.casehub.work.api.WorkItem wi, final Long version) {
        return new WorkItemResponse(
                wi.id(), wi.title(), wi.description(),
                wi.types() == null ? List.of() : List.copyOf(wi.types()),
                wi.formKey(),
                wi.status(), wi.priority(), wi.assigneeId(), wi.owner(),
                wi.candidateGroups(), wi.candidateUsers(), wi.requiredCapabilities(),
                wi.createdBy(), wi.delegationDeclineTarget(), wi.delegationChain(),
                wi.priorStatus(), wi.payload(), wi.resolution(),
                wi.claimDeadline(), wi.expiresAt(), wi.followUpDate(),
                wi.createdAt(), wi.updatedAt(), wi.assignedAt(), wi.startedAt(),
                wi.completedAt(), wi.suspendedAt(),
                wi.labels() == null ? List.of() : wi.labels().stream().map(WorkItemMapper::toLabelResponse).toList(),
                wi.confidenceScore(), wi.callerRef(), version,
                wi.templateId(), wi.templateVersion(), wi.outcome(),
                OutcomeCodecs.decodePermittedOutcomes(wi.permittedOutcomes()),
                wi.inputDataSchema(),
                wi.outputDataSchema(),
                wi.excludedUsers(),
                wi.scope(),
                wi.candidateScores(),
                wi.routingExperiences(),
                wi.compensationStatus(),
                wi.compensatesWorkItemId());
    }

    static Long entityVersion(final java.util.UUID id) {
        if (id == null) return null;
        final io.casehub.work.runtime.model.WorkItemEntity entity =
                io.casehub.work.runtime.model.WorkItemEntity.findById(id);
        return entity != null ? entity.version : null;
    }

    public static io.casehub.work.api.AuditEntryResponse toAuditResponse(final AuditEntry e) {
        return new io.casehub.work.api.AuditEntryResponse(e.id, e.event, e.actor, e.detail, e.occurredAt);
    }

    public static WorkItemWithAuditResponse toWithAudit(final io.casehub.work.api.WorkItem wi, final List<AuditEntry> trail) {
        final List<io.casehub.work.api.AuditEntryResponse> auditResponses = trail.stream()
                                                                                 .map(WorkItemMapper::toAuditResponse)
                                                                                 .toList();
        final List<WorkItemLabelResponse> labelResponses = wi.labels() == null ? List.of()
                                                                               : wi.labels().stream().map(WorkItemMapper::toLabelResponse).toList();
        return new WorkItemWithAuditResponse(
                wi.id(), wi.title(), wi.description(),
                wi.types() == null ? List.of() : List.copyOf(wi.types()),
                wi.formKey(),
                wi.status(), wi.priority(), wi.assigneeId(), wi.owner(),
                wi.candidateGroups(), wi.candidateUsers(), wi.requiredCapabilities(),
                wi.createdBy(), wi.delegationDeclineTarget(), wi.delegationChain(),
                wi.priorStatus(), wi.payload(), wi.resolution(),
                wi.claimDeadline(), wi.expiresAt(), wi.followUpDate(),
                wi.createdAt(), wi.updatedAt(), wi.assignedAt(), wi.startedAt(),
                wi.completedAt(), wi.suspendedAt(),
                labelResponses, auditResponses, wi.confidenceScore(), wi.callerRef(), entityVersion(wi.id()),
                wi.templateId(), wi.templateVersion(), wi.outcome(),
                OutcomeCodecs.decodePermittedOutcomes(wi.permittedOutcomes()),
                wi.inputDataSchema(),
                wi.outputDataSchema(),
                wi.excludedUsers(),
                wi.scope(),
                wi.candidateScores(),
                wi.routingExperiences(),
                wi.compensationStatus(),
                wi.compensatesWorkItemId());
    }

    public static WorkItemCreateRequest toServiceRequest(final CreateWorkItemRequest req) {
        return WorkItemCreateRequest.builder()
                .title(req.title())
                .description(req.description())
                .types(req.types())
                .formKey(req.formKey())
                .priority(req.priority())
                .assigneeId(req.assigneeId())
                .candidateGroups(req.candidateGroups())
                .candidateUsers(req.candidateUsers())
                .requiredCapabilities(req.requiredCapabilities())
                .createdBy(req.createdBy())
                .payload(req.payload())
                .claimDeadline(req.claimDeadline())
                .expiresAt(req.expiresAt())
                .followUpDate(req.followUpDate())
                .labels(req.labels())
                .confidenceScore(req.confidenceScore())
                .callerRef(req.callerRef())
                .claimDeadlineBusinessHours(req.claimDeadlineBusinessHours())
                .expiresAtBusinessHours(req.expiresAtBusinessHours())
                .excludedUsers(req.excludedUsers())
                .scope(req.scope())
                .escalationOnExpiry(req.escalationOnExpiry())
                .escalationOnClaimDeadline(req.escalationOnClaimDeadline())
                .escalationDeadline(req.escalationDeadline())
                .escalationGenerateSummary(req.escalationGenerateSummary())
                .build();
    }

    static WorkItemLabelResponse toLabelResponse(final io.casehub.work.api.WorkItemLabel label) {
        return new WorkItemLabelResponse(label.path(), label.persistence(), label.appliedBy());
    }
}
