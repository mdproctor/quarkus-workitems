package io.quarkiverse.tarkus.runtime.model;

/**
 * Tracks whether a delegated {@link WorkItem} has been picked up by the
 * delegate or is still waiting for acceptance.
 */
public enum DelegationState {

    /** The WorkItem has been forwarded but the delegate has not yet accepted it. */
    PENDING,

    /** The delegate has accepted (or rejected) the delegation. */
    RESOLVED
}
