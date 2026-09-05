package io.casehub.work.runtime.repository;

import io.casehub.work.api.CompensationStatus;
import io.casehub.work.api.WorkItem;
import io.casehub.work.api.WorkItemLabel;
import io.casehub.work.runtime.model.WorkItemEntity;
import io.casehub.work.runtime.model.WorkItemLabelEntity;
import io.casehub.work.runtime.model.WorkItemType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

public final class WorkItemEntityMapper {

    private WorkItemEntityMapper() {}

    public static WorkItem toDomain(WorkItemEntity entity) {
        return WorkItem.builder()
                       .id(entity.id)
                       .tenancyId(entity.tenancyId)
                       .title(entity.title)
                       .description(entity.description)
                       .formKey(entity.formKey)
                       .status(entity.status)
                       .priority(entity.priority)
                       .assigneeId(entity.assigneeId)
                       .owner(entity.owner)
                       .candidateGroups(entity.candidateGroups)
                       .candidateUsers(entity.candidateUsers)
                       .requiredCapabilities(entity.requiredCapabilities)
                       .createdBy(entity.createdBy)
                       .delegationChain(entity.delegationChain)
                       .delegationDeclineTarget(entity.delegationDeclineTarget)
                       .priorStatus(entity.priorStatus)
                       .payload(entity.payload)
                       .resolution(entity.resolution)
                       .claimDeadline(entity.claimDeadline)
                       .expiresAt(entity.expiresAt)
                       .followUpDate(entity.followUpDate)
                       .createdAt(entity.createdAt)
                       .updatedAt(entity.updatedAt)
                       .assignedAt(entity.assignedAt)
                       .startedAt(entity.startedAt)
                       .completedAt(entity.completedAt)
                       .suspendedAt(entity.suspendedAt)
                       .accumulatedUnclaimedSeconds(entity.accumulatedUnclaimedSeconds)
                       .lastReturnedToPoolAt(entity.lastReturnedToPoolAt)
                       .labels(entity.labels.stream()
                                            .map(l -> new WorkItemLabel(l.path, l.persistence, l.appliedBy))
                                            .toList())
                       .types(entity.types.stream()
                                          .map(t -> t.path)
                                          .collect(Collectors.toCollection(LinkedHashSet::new)))
                       .confidenceScore(entity.confidenceScore)
                       .callerRef(entity.callerRef)
                       .parentId(entity.parentId)
                       .scope(entity.scope)
                       .templateId(entity.templateId)
                       .templateVersion(entity.templateVersion)
                       .permittedOutcomes(entity.permittedOutcomes)
                       .excludedUsers(entity.excludedUsers)
                       .outcome(entity.outcome)
                       .inputDataSchema(entity.inputDataSchema)
                       .outputDataSchema(entity.outputDataSchema)
                       .payloadTypeName(entity.payloadTypeName)
                       .resolutionTypeName(entity.resolutionTypeName)
                       .candidateScores(entity.candidateScores)
                       .routingExperiences(entity.routingExperiences)
                       .version(entity.version)
                       .originServiceId(entity.originServiceId)
                       .originWorkItemId(entity.originWorkItemId)
                       .originVersion(entity.originVersion)
                       .escalationOnExpiry(entity.escalationOnExpiry)
                       .escalationOnClaimDeadline(entity.escalationOnClaimDeadline)
                       .escalationDeadline(entity.escalationDeadline)
                       .escalationGenerateSummary(entity.escalationGenerateSummary)
                       .compensationStatus(entity.compensationStatus != null ? entity.compensationStatus : CompensationStatus.NONE)
                       .compensatesWorkItemId(entity.compensatesWorkItemId)
                       .originRef(entity.originRef)
                       .build();}

    public static WorkItemEntity toEntity(WorkItem domain) {
        WorkItemEntity entity = new WorkItemEntity();
        copyFieldsToEntity(entity, domain);
        return entity;
    }

    public static void updateEntity(WorkItemEntity entity, WorkItem domain) {
        copyFieldsToEntity(entity, domain);
    }

    private static void copyFieldsToEntity(WorkItemEntity entity, WorkItem domain) {
        entity.id                          = domain.id();
        entity.tenancyId                   = domain.tenancyId();
        entity.title                       = domain.title();
        entity.description                 = domain.description();
        entity.formKey                     = domain.formKey();
        entity.status                      = domain.status();
        entity.priority                    = domain.priority();
        entity.assigneeId                  = domain.assigneeId();
        entity.owner                       = domain.owner();
        entity.candidateGroups             = domain.candidateGroups();
        entity.candidateUsers              = domain.candidateUsers();
        entity.requiredCapabilities        = domain.requiredCapabilities();
        entity.createdBy                   = domain.createdBy();
        entity.delegationChain             = domain.delegationChain();
        entity.delegationDeclineTarget     = domain.delegationDeclineTarget();
        entity.priorStatus                 = domain.priorStatus();
        entity.payload                     = domain.payload();
        entity.resolution                  = domain.resolution();
        entity.claimDeadline               = domain.claimDeadline();
        entity.expiresAt                   = domain.expiresAt();
        entity.followUpDate                = domain.followUpDate();
        entity.createdAt                   = domain.createdAt();
        entity.updatedAt                   = domain.updatedAt();
        entity.assignedAt                  = domain.assignedAt();
        entity.startedAt                   = domain.startedAt();
        entity.completedAt                 = domain.completedAt();
        entity.suspendedAt                 = domain.suspendedAt();
        entity.accumulatedUnclaimedSeconds = domain.accumulatedUnclaimedSeconds();
        entity.lastReturnedToPoolAt        = domain.lastReturnedToPoolAt();
        entity.labels                      = new ArrayList<>(domain.labels().stream()
                                                                   .map(l -> new WorkItemLabelEntity(l.path(), l.persistence(), l.appliedBy()))
                                                                   .toList());
        entity.types                       = new LinkedHashSet<>(domain.types().stream()
                                                                       .map(WorkItemType::new)
                                                                       .toList());
        entity.confidenceScore             = domain.confidenceScore();
        entity.callerRef                   = domain.callerRef();
        entity.parentId                    = domain.parentId();
        entity.scope                       = domain.scope();
        entity.templateId                  = domain.templateId();
        entity.templateVersion             = domain.templateVersion();
        entity.permittedOutcomes           = domain.permittedOutcomes();
        entity.excludedUsers               = domain.excludedUsers();
        entity.outcome                     = domain.outcome();
        entity.inputDataSchema             = domain.inputDataSchema();
        entity.outputDataSchema            = domain.outputDataSchema();
        entity.payloadTypeName             = domain.payloadTypeName();
        entity.resolutionTypeName          = domain.resolutionTypeName();
        entity.candidateScores             = domain.candidateScores();
        entity.routingExperiences          = domain.routingExperiences();
        entity.originServiceId             = domain.originServiceId();
        entity.originWorkItemId            = domain.originWorkItemId();
        entity.originVersion               = domain.originVersion();
        entity.escalationOnExpiry          = domain.escalationOnExpiry();
        entity.escalationOnClaimDeadline   = domain.escalationOnClaimDeadline();
        entity.escalationDeadline          = domain.escalationDeadline();
        entity.escalationGenerateSummary   = domain.escalationGenerateSummary();
        entity.compensationStatus          = domain.compensationStatus() != null ? domain.compensationStatus() : CompensationStatus.NONE;
        entity.compensatesWorkItemId       = domain.compensatesWorkItemId();
        entity.originRef                   = domain.originRef();}
}
