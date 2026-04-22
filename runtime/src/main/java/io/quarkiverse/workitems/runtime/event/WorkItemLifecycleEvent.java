package io.quarkiverse.workitems.runtime.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkiverse.work.api.WorkEventType;
import io.quarkiverse.work.api.WorkLifecycleEvent;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemStatus;

/**
 * CDI event fired on every WorkItem lifecycle transition.
 * Extends {@link WorkLifecycleEvent} so quarkus-work-core observers (e.g. FilterRegistryEngine)
 * receive it automatically.
 *
 * <p>
 * The WorkItem entity is embedded so observers can access current state without
 * a separate store lookup. All existing accessor methods are preserved.
 *
 * <h2>Firing contract — fires AFTER the mutation is persisted</h2>
 * <p>
 * This event is fired by {@link io.quarkiverse.workitems.runtime.service.WorkItemService}
 * <em>after</em> the WorkItem has been mutated and written to the store via
 * {@code workItemStore.put(workItem)}. By the time any observer receives this event,
 * the WorkItem's new state is already the current state in the store.
 *
 * <p>
 * <strong>This has a critical consequence for observers that need the pre-mutation state.</strong>
 * If an observer calls {@code workItemStore.get(event.workItemId())} inside its handler,
 * it receives the <em>post</em>-mutation entity — the "before" is gone. Observers that
 * must compare before and after (for example, to detect which queues a WorkItem entered
 * or left) must maintain their own record of the previous state between events.
 *
 * <p>
 * The {@code status} field in this event records the status <em>after</em> the transition.
 * No "previous status" field is provided in the event itself.
 */
public final class WorkItemLifecycleEvent extends WorkLifecycleEvent {

    private final String type;
    private final String sourceUri;
    private final String subject;
    private final UUID workItemId;
    private final WorkItemStatus status;
    private final Instant occurredAt;
    private final String actor;
    private final String detail;
    private final String rationale;
    private final String planRef;
    private final WorkItem workItem;

    private WorkItemLifecycleEvent(final String type, final String sourceUri, final String subject,
            final UUID workItemId, final WorkItemStatus status, final Instant occurredAt,
            final String actor, final String detail, final String rationale, final String planRef,
            final WorkItem workItem) {
        this.type = type;
        this.sourceUri = sourceUri;
        this.subject = subject;
        this.workItemId = workItemId;
        this.status = status;
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.detail = detail;
        this.rationale = rationale;
        this.planRef = planRef;
        this.workItem = workItem;
    }

    /**
     * Creates a lifecycle event with the standard WorkItems type prefix.
     *
     * @param eventName the audit event name (e.g. "CREATED") — lowercased automatically
     * @param workItem the WorkItem entity in its post-mutation state
     * @param actor who triggered the transition
     * @param detail optional JSON detail (nullable)
     */
    public static WorkItemLifecycleEvent of(final String eventName, final WorkItem workItem,
            final String actor, final String detail) {
        return new WorkItemLifecycleEvent(
                "io.quarkiverse.workitems.workitem." + eventName.toLowerCase(),
                "/workitems/" + workItem.id,
                workItem.id.toString(),
                workItem.id, workItem.status, Instant.now(),
                actor, detail, null, null, workItem);
    }

    /**
     * Creates a lifecycle event with rationale and plan reference.
     * Used when the actor's stated basis and governing policy are known.
     *
     * @param eventName the audit event name
     * @param workItem the WorkItem entity in its post-mutation state
     * @param actor who triggered the transition
     * @param detail optional JSON detail (nullable)
     * @param rationale the actor's stated basis for the decision (nullable)
     * @param planRef the policy/procedure version that governed this action (nullable)
     */
    public static WorkItemLifecycleEvent of(final String eventName, final WorkItem workItem,
            final String actor, final String detail,
            final String rationale, final String planRef) {
        return new WorkItemLifecycleEvent(
                "io.quarkiverse.workitems.workitem." + eventName.toLowerCase(),
                "/workitems/" + workItem.id,
                workItem.id.toString(),
                workItem.id, workItem.status, Instant.now(),
                actor, detail, rationale, planRef, workItem);
    }

    // ---- Existing accessors preserved (same names as old record components) ----

    /** The CloudEvents type string (e.g. "io.quarkiverse.workitems.workitem.created"). */
    @JsonProperty("type")
    public String type() {
        return type;
    }

    /**
     * The CloudEvents source URI (e.g. "/workitems/{id}").
     * Use {@link #source()} for the WorkItem entity itself.
     */
    @JsonProperty("source")
    public String sourceUri() {
        return sourceUri;
    }

    /** The CloudEvents subject — the WorkItem UUID as a string. */
    @JsonProperty("subject")
    public String subject() {
        return subject;
    }

    /** The affected WorkItem's UUID. */
    @JsonProperty("workItemId")
    public UUID workItemId() {
        return workItemId;
    }

    /** The status AFTER the transition. */
    @JsonProperty("status")
    public WorkItemStatus status() {
        return status;
    }

    /** When this event was created. */
    @JsonProperty("occurredAt")
    public Instant occurredAt() {
        return occurredAt;
    }

    /** Who triggered the transition. */
    @JsonProperty("actor")
    public String actor() {
        return actor;
    }

    /** Optional detail payload (e.g. resolution text, rejection reason). */
    @JsonProperty("detail")
    public String detail() {
        return detail;
    }

    /** The actor's stated basis for the decision (nullable). */
    @JsonProperty("rationale")
    public String rationale() {
        return rationale;
    }

    /** The policy/procedure version that governed this action (nullable). */
    @JsonProperty("planRef")
    public String planRef() {
        return planRef;
    }

    // ---- WorkLifecycleEvent abstract method implementations ----

    @JsonIgnore
    @Override
    public WorkEventType eventType() {
        final String name = type.substring(type.lastIndexOf('.') + 1).toUpperCase();
        try {
            return WorkEventType.valueOf(name);
        } catch (final IllegalArgumentException e) {
            return WorkEventType.CREATED;
        }
    }

    @JsonIgnore
    @Override
    public Map<String, Object> context() {
        return WorkItemContextBuilder.toMap(workItem);
    }

    /**
     * Returns the WorkItem entity in its post-mutation state.
     * Callers needing the CloudEvents source URI should use {@link #sourceUri()} instead.
     */
    @JsonIgnore
    @Override
    public Object source() {
        return workItem;
    }
}
