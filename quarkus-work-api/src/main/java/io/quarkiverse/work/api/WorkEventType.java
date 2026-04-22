package io.quarkiverse.work.api;

/**
 * Canonical lifecycle event vocabulary shared across all work-management systems.
 * WorkItems, CaseHub tasks, and future work-unit types all map to these values.
 */
public enum WorkEventType {
    CREATED,
    ASSIGNED,
    STARTED,
    COMPLETED,
    REJECTED,
    DELEGATED,
    RELEASED,
    SUSPENDED,
    RESUMED,
    CANCELLED,
    EXPIRED,
    /** Claim deadline passed without the work being claimed. */
    CLAIM_EXPIRED,
    ESCALATED
}
