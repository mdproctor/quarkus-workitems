package io.casehub.work.runtime.event;

import io.casehub.work.api.Outcome;
import io.casehub.work.runtime.model.OutcomeCodecs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a flat {@code Map<String, Object>} context from a WorkItem entity.
 *
 * <p>
 * Direct field access is used instead of reflection because Hibernate ORM bytecode
 * enhancement intercepts field storage — {@code Field.get()} via reflection returns
 * stale values. Direct access triggers the enhanced getter and returns the correct value.
 *
 * <p>
 * Enum fields are preserved as enum constants so JEXL can call enum methods
 * (e.g. {@code workItem.priority.name()}).
 */
public final class WorkItemContextBuilder {

    private WorkItemContextBuilder() {
    }

    public static Map<String, Object> toMap(final io.casehub.work.api.WorkItem workItem) {
        final Map<String, Object> map = new HashMap<>();
        map.put("id", workItem.id());
        map.put("title", workItem.title());
        map.put("description", workItem.description());
        map.put("types", workItem.types() != null ? List.copyOf(workItem.types()) : List.of());
        map.put("formKey", workItem.formKey());
        map.put("status", workItem.status());
        map.put("priority", workItem.priority());
        map.put("assigneeId", workItem.assigneeId());
        map.put("owner", workItem.owner());
        map.put("candidateGroups", workItem.candidateGroups());
        map.put("candidateUsers", workItem.candidateUsers());
        map.put("requiredCapabilities", workItem.requiredCapabilities());
        map.put("createdBy", workItem.createdBy());
        map.put("delegationDeclineTarget", workItem.delegationDeclineTarget());
        map.put("delegationChain", workItem.delegationChain());
        map.put("priorStatus", workItem.priorStatus());
        map.put("payload", workItem.payload());
        map.put("resolution", workItem.resolution());
        map.put("claimDeadline", workItem.claimDeadline());
        map.put("expiresAt", workItem.expiresAt());
        map.put("followUpDate", workItem.followUpDate());
        map.put("createdAt", workItem.createdAt());
        map.put("updatedAt", workItem.updatedAt());
        map.put("assignedAt", workItem.assignedAt());
        map.put("startedAt", workItem.startedAt());
        map.put("completedAt", workItem.completedAt());
        map.put("suspendedAt", workItem.suspendedAt());
        map.put("accumulatedUnclaimedSeconds", workItem.accumulatedUnclaimedSeconds());
        map.put("lastReturnedToPoolAt", workItem.lastReturnedToPoolAt());
        map.put("labels", workItem.labels() != null ? workItem.labels().stream().map(io.casehub.work.api.WorkItemLabel::path).toList() : List.of());
        map.put("confidenceScore", workItem.confidenceScore());
        map.put("callerRef", workItem.callerRef());
        map.put("parentId", workItem.parentId() != null ? workItem.parentId().toString() : null);
        map.put("templateId", workItem.templateId() != null ? workItem.templateId().toString() : null);
        map.put("templateVersion", workItem.templateVersion());
        final List<Outcome> permittedDefs = OutcomeCodecs.decodePermittedOutcomes(workItem.permittedOutcomes());
        map.put("permittedOutcomes",
                permittedDefs == null ? null : permittedDefs.stream().map(Outcome::name).toList());
        map.put("outcome", workItem.outcome());
        map.put("inputDataSchema", workItem.inputDataSchema());
        map.put("outputDataSchema", workItem.outputDataSchema());
        map.put("excludedUsers", workItem.excludedUsers());
        map.put("scope", workItem.scope());
        map.put("tenancyId", workItem.tenancyId());
        map.put("payloadTypeName", workItem.payloadTypeName());
        map.put("resolutionTypeName", workItem.resolutionTypeName());
        map.put("candidateScores", workItem.candidateScores());
        map.put("routingExperiences", workItem.routingExperiences());
        map.put("version", workItem.version());
        map.put("originServiceId", workItem.originServiceId());
        map.put("originWorkItemId", workItem.originWorkItemId());
        map.put("originVersion", workItem.originVersion());
        map.put("escalationOnExpiry", workItem.escalationOnExpiry());
        map.put("escalationOnClaimDeadline", workItem.escalationOnClaimDeadline());
        map.put("escalationDeadline", workItem.escalationDeadline());
        map.put("escalationGenerateSummary", workItem.escalationGenerateSummary());
        map.put("compensationStatus", workItem.compensationStatus() != null ? workItem.compensationStatus().name() : "NONE");
        map.put("compensatesWorkItemId", workItem.compensatesWorkItemId());
        return map;
    }
}
