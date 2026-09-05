package io.casehub.work.api;

import java.util.UUID;

public record WorkItemRef(
    UUID id,
    WorkItemStatus status,
    String callerRef,
    String assigneeId,
    String resolution,
    String candidateGroups,
    String outcome,
    String tenancyId,
    String payload,
    String payloadTypeName,
    String resolutionTypeName,
    String originRef
) {}
