package io.casehub.work.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight status-change event carrying only api-module types.
 * This is the SPI-visible subset of the runtime's WorkItemLifecycleEvent.
 */
public record WorkItemStatusEvent(
    WorkEventType eventType,
    UUID workItemId,
    WorkItemStatus status,
    String actor,
    String detail,
    String callerRef,
    String assigneeId,
    String candidateGroups,
    String outcome,
    String tenancyId,
    Instant occurredAt,
    String originRef
) {}
