package io.quarkiverse.workitems.spi;

/** Lifecycle events that trigger worker (re-)selection. */
public enum AssignmentTrigger {
    /** WorkItem first created and persisted. */
    CREATED,
    /** WorkItem returned to pool by its assignee (release). */
    RELEASED,
    /** WorkItem delegated to a different pool or individual. */
    DELEGATED
}
