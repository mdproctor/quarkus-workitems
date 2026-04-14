package io.quarkiverse.tarkus.runtime.event;

import java.time.Instant;
import java.util.UUID;

import io.quarkiverse.tarkus.runtime.model.WorkItemStatus;

/**
 * CDI event fired on every WorkItem lifecycle transition.
 * Consuming applications observe this with {@code @Observes WorkItemLifecycleEvent}
 * and can bridge it to any messaging system.
 */
public record WorkItemLifecycleEvent(
        String type,
        String source,
        String subject,
        UUID workItemId,
        WorkItemStatus status,
        Instant occurredAt,
        String actor,
        String detail) {

    /**
     * Creates a lifecycle event with the standard Tarkus type prefix.
     *
     * @param eventName the audit event name (e.g. "CREATED") — lowercased automatically
     * @param workItemId the affected WorkItem
     * @param status the status AFTER the transition
     * @param actor who triggered the transition
     * @param detail optional JSON detail (nullable)
     */
    public static WorkItemLifecycleEvent of(final String eventName, final UUID workItemId,
            final WorkItemStatus status, final String actor, final String detail) {
        return new WorkItemLifecycleEvent(
                "io.quarkiverse.tarkus.workitem." + eventName.toLowerCase(),
                "/tarkus/workitems/" + workItemId,
                workItemId.toString(),
                workItemId,
                status,
                Instant.now(),
                actor,
                detail);
    }
}
