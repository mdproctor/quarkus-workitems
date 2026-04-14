package io.quarkiverse.tarkus.runtime.model;

/**
 * Priority level of a {@link WorkItem}, used to drive inbox ordering and
 * escalation policy thresholds.
 */
public enum WorkItemPriority {

    /** Low priority — attend to when time permits. */
    LOW,

    /** Normal priority — standard processing order. */
    NORMAL,

    /** High priority — handle before LOW and NORMAL items. */
    HIGH,

    /** Critical priority — requires immediate attention. */
    CRITICAL
}
