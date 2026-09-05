package io.casehub.work.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.casehub.platform.api.subscription.SubscribableEvent;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class WorkItemLifecycleEvent implements WorkItemEvent, SubscribableEvent {

    private final String         type;
    private final String         sourceUri;
    private final String         subject;
    private final UUID           workItemId;
    private final WorkItemStatus status;
    private final Instant        occurredAt;
    private final String         actor;
    private final String         detail;
    private final String         rationale;
    private final String         planRef;
    private final String         outcome;
    private final String         tenancyId;
    private final String         callerRef;
    private final String         assigneeId;
    private final String         resolution;
    private final String         candidateGroups;
    private final List<String>   types;
    private final WorkItem workItem;
    private       UUID     ledgerEntryId;


    private WorkItemLifecycleEvent(final String type, final String sourceUri, final String subject,
                                   final UUID workItemId, final WorkItemStatus status, final Instant occurredAt,
                                   final String actor, final String detail, final String rationale, final String planRef,
                                   final String outcome, final String tenancyId,
                                   final String callerRef, final String assigneeId, final String resolution, final String candidateGroups,
                                   final List<String> types,
                                   final WorkItem workItem) {
        this.type            = type;
        this.sourceUri       = sourceUri;
        this.subject         = subject;
        this.workItemId      = workItemId;
        this.status          = status;
        this.occurredAt      = occurredAt;
        this.actor           = actor;
        this.detail          = detail;
        this.rationale       = rationale;
        this.planRef         = planRef;
        this.outcome         = outcome;
        this.tenancyId       = tenancyId;
        this.callerRef       = callerRef;
        this.assigneeId      = assigneeId;
        this.resolution      = resolution;
        this.candidateGroups = candidateGroups;
        this.types           = types;
        this.workItem        = workItem;
    }

    public static WorkItemLifecycleEvent of(final String eventName, final WorkItem workItem,
                                            final String actor, final String detail) {
        return new WorkItemLifecycleEvent(
                WorkCloudEventTypes.PREFIX + eventName.toLowerCase(Locale.ROOT),
                "/workitems/" + workItem.id(),
                workItem.id().toString(),
                workItem.id(), workItem.status(), Instant.now(),
                actor, detail, null, null, workItem.outcome(), workItem.tenancyId(),
                workItem.callerRef(), workItem.assigneeId(), workItem.resolution(), workItem.candidateGroups(),
                workItem.types() != null ? List.copyOf(workItem.types()) : List.of(),
                workItem);
    }

    public static WorkItemLifecycleEvent of(final String eventName, final WorkItem workItem,
                                            final String actor, final String detail,
                                            final String rationale, final String planRef) {
        return new WorkItemLifecycleEvent(
                WorkCloudEventTypes.PREFIX + eventName.toLowerCase(Locale.ROOT),
                "/workitems/" + workItem.id(),
                workItem.id().toString(),
                workItem.id(), workItem.status(), Instant.now(),
                actor, detail, rationale, planRef, workItem.outcome(), workItem.tenancyId(),
                workItem.callerRef(), workItem.assigneeId(), workItem.resolution(), workItem.candidateGroups(),
                workItem.types() != null ? List.copyOf(workItem.types()) : List.of(),
                workItem);
    }

    /**
     * Reconstructs a lifecycle event from wire-format fields — for use by distributed
     * broadcaster implementations that receive serialised events from other nodes.
     *
     * <p>
     * The {@code workItem} entity is {@code null} on the receiving node. This is intentional:
     * the SSE endpoint serialises only the scalar fields (workItem is {@code @JsonIgnore}),
     * so SSE clients receive identical output regardless of whether the event originated
     * locally or was reconstructed from the wire. Callers must not invoke {@link #workItem()}
     * or {@link #context()} on wire-reconstructed events.
     */
    public static WorkItemLifecycleEvent fromWire(final String type, final String sourceUri,
                                                  final String subject, final UUID workItemId, final WorkItemStatus status,
                                                  final Instant occurredAt, final String actor, final String detail,
                                                  final String rationale, final String planRef, final String outcome, final String tenancyId,
                                                  final String callerRef, final String assigneeId, final String resolution, final String candidateGroups,
                                                  final List<String> types, final UUID ledgerEntryId) {
        var event = new WorkItemLifecycleEvent(type, sourceUri, subject, workItemId, status,
                                          occurredAt, actor, detail, rationale, planRef, outcome, tenancyId,
                                          callerRef, assigneeId, resolution, candidateGroups, types, null);
        event.ledgerEntryId = ledgerEntryId;
        return event;
    }

    // ---- Existing accessors preserved (same names as old record components) ----

    /**
     * The CloudEvents type string (e.g. "io.casehub.work.workitem.created").
     */
    @JsonProperty("type")
    public String type() {
        return type;
    }

    /**
     * The CloudEvents source URI (e.g. "/workitems/{id}").
     * Use {@link #workItem()} for the WorkItem itself.
     */
    @JsonProperty("source")
    public String sourceUri() {
        return sourceUri;
    }

    /**
     * The CloudEvents subject — the WorkItem UUID as a string.
     */
    @JsonProperty("subject")
    public String subject() {
        return subject;
    }

    /**
     * The affected WorkItem's UUID.
     */
    @JsonProperty("workItemId")
    public UUID workItemId() {
        return workItemId;
    }

    /**
     * The status AFTER the transition.
     */
    @JsonProperty("status")
    public WorkItemStatus status() {
        return status;
    }

    /**
     * When this event was created.
     */
    @JsonProperty("occurredAt")
    @Override
    public Instant occurredAt() {
        return occurredAt;
    }

    /**
     * Who triggered the transition.
     */
    @JsonProperty("actor")
    @Override
    public String actor() {
        return actor;
    }

    /**
     * Optional detail payload (e.g. resolution text, rejection reason).
     */
    @JsonProperty("detail")
    @Override
    public String detail() {
        return detail;
    }

    /**
     * The actor's stated basis for the decision (nullable).
     */
    @JsonProperty("rationale")
    public String rationale() {
        return rationale;
    }

    /**
     * The policy/procedure version that governed this action (nullable).
     */
    @JsonProperty("planRef")
    public String planRef() {
        return planRef;
    }

    /**
     * The named outcome recorded at completion (e.g. {@code "approved"}, {@code "rejected"}).
     *
     * <p>
     * Null in two distinct cases:
     * <ol>
     * <li>Non-completion events (CREATED, ASSIGNED, etc.) — no outcome is applicable.</li>
     * <li>System-initiated completions via {@code completeFromSystem()} (e.g. multi-instance
     *     threshold reached by {@code MultiInstanceGroupPolicy}) — no human-assigned outcome.</li>
     * </ol>
     * Observers that switch on outcome must handle null explicitly.
     */
    @JsonProperty("outcome")
    public String outcome() {
        return outcome;
    }

    /**
     * The tenancy ID of the WorkItem this event belongs to.
     * Server-side only — never serialised to SSE clients.
     */
    @JsonIgnore
    @Override
    public String tenancyId() {
        return tenancyId;
    }

    /**
     * The callerRef from the WorkItem (external correlation identifier).
     * For wire-reconstructed events, this is stored independently; for local events,
     * it is read from the embedded workItem entity.
     */
    @JsonProperty("callerRef")
    public String callerRef() {
        return callerRef;
    }

    /**
     * The assigneeId from the WorkItem (who is assigned to complete this work).
     * For wire-reconstructed events, this is stored independently; for local events,
     * it is read from the embedded workItem entity.
     */
    @JsonProperty("assigneeId")
    public String assigneeId() {
        return assigneeId;
    }

    /**
     * The resolution JSON from the WorkItem.
     * For wire-reconstructed events, this is stored independently; for local events,
     * it is read from the embedded workItem entity.
     */
    @JsonProperty("resolution")
    public String resolution() {
        return resolution;
    }

    /**
     * The candidateGroups from the WorkItem (comma-separated list of eligible groups).
     * For wire-reconstructed events, this is stored independently; for local events,
     * it is read from the embedded workItem entity.
     */
    @JsonProperty("candidateGroups")
    public String candidateGroups() {
        return candidateGroups;
    }

    /**
     * The types from the WorkItem (path-based type classification).
     * For wire-reconstructed events, this is stored independently; for local events,
     * it is read from the embedded workItem entity.
     */
    @JsonProperty("types")
    public List<String> types() {
        return types;
    }

    /**
     * The work-ledger entry ID for this lifecycle transition. Set by
     * {@code LedgerEventCapture} after persisting the entry — null if
     * the ledger module is absent or this event was constructed without one.
     */
    @Override
    public UUID ledgerEntryId() {
        return ledgerEntryId;
    }

    /**
     * Returns the SPI accessor for setting ledgerEntryId on an event. Only
     * {@code LedgerEventCapture} should call this — the public event API stays immutable.
     */
    private static final LedgerEntryIdSetter LEDGER_ENTRY_ID_SETTER = (event, id) -> event.ledgerEntryId = id;

    public static LedgerEntryIdSetter ledgerEntryIdSetter() {
        return LEDGER_ENTRY_ID_SETTER;
    }


    // ---- WorkItemEvent interface implementation ----

    @JsonIgnore
    @Override
    public WorkItemRef ref() {
        if (workItem != null) {
            return new WorkItemRef(workItemId, status, workItem.callerRef(), workItem.assigneeId(),
                                   workItem.resolution(), workItem.candidateGroups(), outcome, tenancyId,
                                   workItem.payload(), workItem.payloadTypeName(), workItem.resolutionTypeName(),
                                   workItem.originRef());
        }
        return new WorkItemRef(workItemId, status, callerRef, assigneeId,
                               resolution, candidateGroups, outcome, tenancyId, null, null, null, null);
    }

    @JsonIgnore
    @Override
    public WorkEventType eventType() {
        final String name = type.substring(type.lastIndexOf('.') + 1).toUpperCase();
        return WorkEventType.valueOf(name);
    }

    // ---- Runtime-specific methods (not on any interface) ----

    @JsonIgnore
    public Map<String, Object> context() {
        return Map.of();
    }

    @JsonIgnore
    public WorkItem workItem() {
        return workItem;
    }
}
