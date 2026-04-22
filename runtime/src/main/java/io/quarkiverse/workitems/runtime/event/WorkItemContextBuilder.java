package io.quarkiverse.workitems.runtime.event;

import java.util.HashMap;
import java.util.Map;

import io.quarkiverse.workitems.runtime.model.WorkItem;

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

    /**
     * Converts a WorkItem to a {@code Map<String, Object>} by reading all known public
     * fields directly (not via reflection).
     *
     * @param workItem the WorkItem to convert
     * @return map of field name to field value (null values are included)
     */
    public static Map<String, Object> toMap(final WorkItem workItem) {
        final Map<String, Object> map = new HashMap<>();
        map.put("id", workItem.id);
        map.put("version", workItem.version);
        map.put("title", workItem.title);
        map.put("description", workItem.description);
        map.put("category", workItem.category);
        map.put("formKey", workItem.formKey);
        map.put("status", workItem.status);
        map.put("priority", workItem.priority);
        map.put("assigneeId", workItem.assigneeId);
        map.put("owner", workItem.owner);
        map.put("candidateGroups", workItem.candidateGroups);
        map.put("candidateUsers", workItem.candidateUsers);
        map.put("requiredCapabilities", workItem.requiredCapabilities);
        map.put("createdBy", workItem.createdBy);
        map.put("delegationState", workItem.delegationState);
        map.put("delegationChain", workItem.delegationChain);
        map.put("priorStatus", workItem.priorStatus);
        map.put("payload", workItem.payload);
        map.put("resolution", workItem.resolution);
        map.put("claimDeadline", workItem.claimDeadline);
        map.put("expiresAt", workItem.expiresAt);
        map.put("followUpDate", workItem.followUpDate);
        map.put("createdAt", workItem.createdAt);
        map.put("updatedAt", workItem.updatedAt);
        map.put("assignedAt", workItem.assignedAt);
        map.put("startedAt", workItem.startedAt);
        map.put("completedAt", workItem.completedAt);
        map.put("suspendedAt", workItem.suspendedAt);
        map.put("labels", workItem.labels);
        map.put("confidenceScore", workItem.confidenceScore);
        return map;
    }
}
