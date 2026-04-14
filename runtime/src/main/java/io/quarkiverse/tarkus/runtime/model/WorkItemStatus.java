package io.quarkiverse.tarkus.runtime.model;

/**
 * Lifecycle status of a {@link WorkItem}.
 */
public enum WorkItemStatus {

    /** WorkItem has been created but not yet assigned to anyone. */
    PENDING,

    /** WorkItem has been assigned to a specific assignee but work has not started. */
    ASSIGNED,

    /** WorkItem is actively being worked on by the assignee. */
    IN_PROGRESS,

    /** WorkItem has been completed successfully. */
    COMPLETED,

    /** WorkItem was rejected by the assignee or a reviewer. */
    REJECTED,

    /** WorkItem has been delegated to another actor. */
    DELEGATED,

    /** WorkItem has been temporarily suspended and is awaiting resumption. */
    SUSPENDED,

    /** WorkItem was cancelled before completion. */
    CANCELLED,

    /** WorkItem's deadline passed without resolution. */
    EXPIRED,

    /** WorkItem was escalated due to expiry or policy breach. */
    ESCALATED;

    /**
     * Returns {@code true} if this status represents a terminal (end) state from
     * which the WorkItem cannot transition further under normal lifecycle rules.
     * Terminal statuses are: {@link #COMPLETED}, {@link #REJECTED},
     * {@link #CANCELLED}, and {@link #ESCALATED}.
     *
     * @return {@code true} when the WorkItem has reached a terminal state
     */
    public boolean isTerminal() {
        return switch (this) {
            case COMPLETED, REJECTED, CANCELLED, ESCALATED -> true;
            default -> false;
        };
    }

    /**
     * Returns {@code true} if this status represents an active (non-terminal, non-expired)
     * state in which the WorkItem is still being processed.
     * Active statuses are: {@link #PENDING}, {@link #ASSIGNED},
     * {@link #IN_PROGRESS}, and {@link #SUSPENDED}.
     *
     * @return {@code true} when the WorkItem is still actively in progress
     */
    public boolean isActive() {
        return switch (this) {
            case PENDING, ASSIGNED, IN_PROGRESS, SUSPENDED -> true;
            default -> false;
        };
    }
}
