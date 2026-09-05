package io.casehub.work.api;

/** Denormalized compensation state of a WorkItem whose effects are being reversed. */
public enum CompensationStatus {
    /** No compensation activity. */
    NONE,
    /** A compensating WorkItem has been created and is in progress. */
    COMPENSATING,
    /** The compensating WorkItem has completed — this WorkItem's effects are reversed. */
    COMPENSATED
}
