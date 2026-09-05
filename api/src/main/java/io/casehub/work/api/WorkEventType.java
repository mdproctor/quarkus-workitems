package io.casehub.work.api;

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
    /** System or infrastructure failure. */
    FAULTED,
    DELEGATED,
    /** Delegatee accepted a targeted delegation. */
    DELEGATION_ACCEPTED,
    /** Delegatee declined a targeted delegation. */
    DELEGATION_DECLINED,
    RELEASED,
    SUSPENDED,
    RESUMED,
    CANCELLED,
    /** WorkItem superseded by context change. */
    OBSOLETE,
    EXPIRED,
    /** Claim deadline passed without the work being claimed. */
    CLAIM_EXPIRED,
    /** Child WorkItems were spawned from this work unit. */
    SPAWNED,
    ESCALATED,
    /** expiresAt deadline was extended forward by an actor or breach policy. */
    DEADLINE_EXTENDED,
    /** WorkItem re-routed to new candidate groups by SLA breach policy. */
    SLA_REASSIGNED,
    /** SLA breach policy extended the deadline. */
    SLA_EXTENDED,
    /** An external signal was received and routed to this work unit. */
    SIGNAL_RECEIVED,
    /** Actor explicitly escalated the WorkItem to a different candidate group. */
    MANUALLY_ESCALATED,
    /** Actor reported progress (percentComplete, statusNote). */
    PROGRESS_UPDATE,
    /** A label was added to the WorkItem. */
    LABEL_ADDED,
    /** A label was removed from the WorkItem. */
    LABEL_REMOVED,
    /** A compensating WorkItem was created; original marked COMPENSATING. */
    COMPENSATION_STARTED,
    /** The compensating WorkItem completed; original marked COMPENSATED. */
    COMPENSATION_COMPLETED
}
