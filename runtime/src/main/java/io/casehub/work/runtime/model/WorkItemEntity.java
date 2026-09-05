package io.casehub.work.runtime.model;

import io.casehub.work.api.CompensationStatus;
import io.casehub.work.api.spi.ClaimSlaPolicy;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Core entity representing a unit of work requiring human attention or judgment.
 *
 * <p>
 * A {@code WorkItem} is deliberately NOT called {@code Task} to avoid naming conflicts
 * with the CNCF Serverless Workflow SDK (used by Quarkus-Flow) and CaseHub, both of which
 * define their own {@code Task} types for machine-executed steps.
 *
 * <p>
 * Lifecycle transitions are managed by {@code WorkItemService}. All status changes are
 * recorded in the immutable {@link AuditEntry} log.
 */
@Entity
@Table(name = "work_item")
public class WorkItemEntity extends PanacheEntityBase {

    /** Primary key — UUID assigned on first persist. */
    @Id
    public UUID id;

    /**
     * JPA optimistic locking version — incremented on every successful UPDATE.
     *
     * <p>
     * Hibernate includes this in every UPDATE WHERE clause:
     * {@code WHERE id = ? AND version = N}. If another node modified the row
     * (bumping version to N+1), the WHERE matches zero rows and Hibernate throws
     * {@code OptimisticLockException}, which the REST layer maps to HTTP 409 Conflict.
     *
     * <p>
     * This makes {@code PUT /workitems/{id}/claim} atomic across a cluster: two
     * nodes racing to claim the same PENDING WorkItem cannot both succeed — the
     * second receives a 409 and retries with fresh data.
     */
    @Version
    @Column(nullable = false)
    public Long version = 0L;

    @Column(name = "tenancy_id", nullable = false)
    public String tenancyId;

    // -------------------------------------------------------------------------
    // Core descriptive fields
    // -------------------------------------------------------------------------

    /** Short human-readable title. */
    public String title;

    /** Detailed description of what needs to be done. */
    public String description;

    /** Key identifying the UI form to render for this work item. */
    @Column(name = "form_key")
    public String formKey;

    // -------------------------------------------------------------------------
    // Status and priority
    // -------------------------------------------------------------------------

    /** Current lifecycle status of this work item. */
    @Enumerated(EnumType.STRING)
    public io.casehub.work.api.WorkItemStatus status;

    /** Priority level driving inbox ordering and escalation thresholds. */
    @Enumerated(EnumType.STRING)
    public io.casehub.work.api.WorkItemPriority priority;

    // -------------------------------------------------------------------------
    // Assignment
    // -------------------------------------------------------------------------

    /** Identity of the actor currently assigned to work on this item. */
    @Column(name = "assignee_id")
    public String assigneeId;

    /**
     * Identity of the actor who owns this work item (may differ from assignee
     * after delegation).
     */
    public String owner;

    /** Comma-separated group identifiers eligible to claim this item. */
    @Column(name = "candidate_groups")
    public String candidateGroups;

    /** Comma-separated user identifiers eligible to claim this item. */
    @Column(name = "candidate_users")
    public String candidateUsers;

    /** Comma-separated capability tags the assignee must possess. */
    @Column(name = "required_capabilities")
    public String requiredCapabilities;

    /** Identity of the actor or system that created this work item. */
    @Column(name = "created_by")
    public String createdBy;

    // -------------------------------------------------------------------------
    // Delegation
    // -------------------------------------------------------------------------

    /**
     * Comma-separated list of actorIds who have delegated this item (most recent last).
     * Format is CSV; actorIds are UUIDs (hyphens only, no commas) so the format is unambiguous.
     * Structured per-hop data (timestamps, reasons) is deferred to #240.
     */
    @Column(name = "delegation_chain")
    public String delegationChain;

    /**
     * Instance-level override for where this item returns when a delegation is declined.
     * Null means use the scope preference (casehub.work.delegation.decline-target, default POOL).
     * Set by delegate(); cleared after acceptDelegation() or declineDelegation().
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "delegation_decline_target", length = 10)
    public io.casehub.work.api.DeclineTarget delegationDeclineTarget;

    /**
     * Status snapshot saved immediately before a suspend so that resume can
     * restore the correct pre-suspension status.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "prior_status")
    public io.casehub.work.api.WorkItemStatus priorStatus;

    // -------------------------------------------------------------------------
    // Payload and resolution
    // -------------------------------------------------------------------------

    /** Arbitrary JSON payload carrying business context for this work item. */
    @Column(columnDefinition = "TEXT")
    public String payload;

    /** Free-text or JSON explanation recorded when the work item is completed or rejected. */
    @Column(columnDefinition = "TEXT")
    public String resolution;

    // -------------------------------------------------------------------------
    // Deadlines
    // -------------------------------------------------------------------------

    /** Instant by which the item must be claimed; drives claim-deadline escalation. */
    @Column(name = "claim_deadline")
    public Instant claimDeadline;

    /** Instant by which the item must be completed; drives expiry escalation. */
    @Column(name = "expires_at")
    public Instant expiresAt;

    /** Optional follow-up reminder date used for inbox filtering. */
    @Column(name = "follow_up_date")
    public Instant followUpDate;

    // -------------------------------------------------------------------------
    // Timestamps
    // -------------------------------------------------------------------------

    /** When the work item was first persisted. */
    @Column(name = "created_at")
    public Instant createdAt;

    /** When the work item was last modified. */
    @Column(name = "updated_at")
    public Instant updatedAt;

    /** When the item transitioned into {@link io.casehub.work.api.WorkItemStatus#ASSIGNED}. */
    @Column(name = "assigned_at")
    public Instant assignedAt;

    /** When the item transitioned into {@link io.casehub.work.api.WorkItemStatus#IN_PROGRESS}. */
    @Column(name = "started_at")
    public Instant startedAt;

    /** When the item transitioned into a terminal status. */
    @Column(name = "completed_at")
    public Instant completedAt;

    /** When the item transitioned into {@link io.casehub.work.api.WorkItemStatus#SUSPENDED}. */
    @Column(name = "suspended_at")
    public Instant suspendedAt;

    // -------------------------------------------------------------------------
    // Claim SLA tracking
    // -------------------------------------------------------------------------

    /**
     * Total seconds this item has spent in the unclaimed pool across all previous
     * PENDING phases. Updated when the item is claimed; used by {@link ClaimSlaPolicy}
     * to compute the remaining pool budget.
     */
    @Column(name = "accumulated_unclaimed_seconds", nullable = false)
    public long accumulatedUnclaimedSeconds = 0L;

    /**
     * When this item most recently entered the unclaimed pool (PENDING state).
     * Set to {@code createdAt} on creation; updated on release, delegation return, and
     * after each claim-deadline expiry. Null while the item is held by a claimant.
     */
    @Column(name = "last_returned_to_pool_at")
    public Instant lastReturnedToPoolAt;

    // -------------------------------------------------------------------------
    // Labels
    // -------------------------------------------------------------------------

    /**
     * Labels attached to this WorkItem.
     * {@link io.casehub.work.api.LabelPersistence#MANUAL} labels are applied by humans.
     * {@link io.casehub.work.api.LabelPersistence#INFERRED} labels are maintained by the filter engine.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "work_item_label", joinColumns = @JoinColumn(name = "work_item_id"))
    public List<WorkItemLabelEntity> labels = new ArrayList<>();

    /**
     * Types attached to this WorkItem.
     * Types are hierarchical paths (e.g. "approval", "compliance/audit") that classify
     * the WorkItem for routing, filtering, and reporting.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "work_item_type", joinColumns = @JoinColumn(name = "work_item_id"))
    public Set<WorkItemType> types = new LinkedHashSet<>();

    // -------------------------------------------------------------------------
    // AI metadata
    // -------------------------------------------------------------------------

    /**
     * Confidence score from the AI agent that created this WorkItem (0.0–1.0).
     * Null when created by a human or when no confidence metadata was provided.
     */
    @Column(name = "confidence_score")
    public Double confidenceScore;

    // -------------------------------------------------------------------------
    // Spawn routing
    // -------------------------------------------------------------------------

    /**
     * Opaque caller-supplied routing key set at spawn time.
     * quarkus-work stores and echoes this in every lifecycle event; it never
     * interprets it. CaseHub embeds its {@code caseId:planItemId} here so that
     * child completion events can be routed back to the right PlanItem without
     * a query. Null for WorkItems not created via spawn.
     */
    @Column(name = "caller_ref", length = 512)
    public String callerRef;

    /**
     * UUID of the parent WorkItem for multi-instance groups.
     * Null for standalone WorkItems. Non-null means this item is a child instance;
     * the parent is the group coordinator or participant root.
     */
    @Column(name = "parent_id")
    public UUID parentId;

    /**
     * Hierarchical scope path in slash-separated form — e.g. {@code "casehubio/devtown/pr-review"}.
     * Null means unscoped (root); the expiry service falls back to {@code Path.root()}.
     * Set by the caller at creation time; snapshotted from the template if present.
     * See engine#330 for engine-side propagation.
     */
    @Column(name = "scope")
    public String scope;

    // -------------------------------------------------------------------------
    // Named outcomes (Refs #169)
    // -------------------------------------------------------------------------

    /**
     * UUID of the WorkItemTemplate this item was instantiated from.
     * Null for items created directly (not via template). Used for provenance and
     * as a lookup key for outcome display names via the template endpoint.
     */
    @Column(name = "template_id")
    public UUID templateId;

    /**
     * Version of the WorkItemTemplate at the time this item was instantiated.
     * Null for items created directly (not via template). Together with
     * {@link #templateId}, enables full audit of which template version
     * governed this WorkItem's schema and permitted outcomes.
     */
    @Column(name = "template_version")
    public Long templateVersion;

    /**
     * JSON array of permitted outcome names snapshotted from the template at instantiation.
     * Example: {@code ["approved","rejected","needs-revision"]}
     * Null means no constraint — any outcome (or none) is accepted at completion.
     * Set by {@code WorkItemTemplateService.instantiate()}; never modified after creation.
     */
    @Column(name = "permitted_outcomes", columnDefinition = "TEXT")
    public String permittedOutcomes;

    /** Comma-separated user IDs excluded from claiming this WorkItem. Snapshotted from
     *  {@link io.casehub.work.runtime.model.WorkItemTemplate#excludedUsers} at instantiation. Refs #171. */
    @Column(name = "excluded_users", columnDefinition = "TEXT")
    public String excludedUsers;

    /**
     * The outcome name recorded when this item reached {@link io.casehub.work.api.WorkItemStatus#COMPLETED}.
     * Null until the item is completed. Validated against {@link #permittedOutcomes}
     * when that field is non-null.
     */
    @Column(name = "outcome", length = 255)
    public String outcome;

    /**
     * JSON Schema (draft-07) for {@link #payload}, snapshotted from the template at
     * instantiation. Null for WorkItems created without a template or from a template
     * that declares no input schema. Validated by {@code WorkItemService.create()}.
     */
    @Column(name = "input_data_schema", columnDefinition = "TEXT")
    public String inputDataSchema;

    /**
     * JSON Schema (draft-07) for {@link #resolution}, snapshotted from the template at
     * instantiation. Null means no output constraint. Validated by
     * {@code WorkItemService.complete()}.
     */
    @Column(name = "output_data_schema", columnDefinition = "TEXT")
    public String outputDataSchema;

    @Column(name = "payload_type_name")
    public String payloadTypeName;

    @Column(name = "resolution_type_name")
    public String resolutionTypeName;

    @Column(name = "candidate_scores", columnDefinition = "TEXT")
    public String candidateScores;

    @Column(name = "routing_experiences", columnDefinition = "TEXT")
    public String routingExperiences;
    @Column(name = "origin_service_id")
    public String originServiceId;
    @Column(name = "origin_work_item_id")
    public UUID   originWorkItemId;
    @Column(name = "origin_version")
    public Long   originVersion;

    @Column(name = "escalation_on_expiry")
    public String escalationOnExpiry;

    @Column(name = "escalation_on_claim_deadline")
    public String escalationOnClaimDeadline;

    @Column(name = "escalation_deadline", length = 32)
    public String escalationDeadline;

    @Column(name = "escalation_generate_summary")
    public Boolean escalationGenerateSummary;
    @Enumerated(EnumType.STRING)
    @Column(name = "compensation_status")
    public CompensationStatus compensationStatus = CompensationStatus.NONE;

    @Column(name = "compensates_work_item_id")
    public UUID compensatesWorkItemId;

    @Column(name = "origin_ref")
    public String originRef;


    // -------------------------------------------------------------------------
    // JPA lifecycle callbacks
    // -------------------------------------------------------------------------

    /**
     * Assigns a UUID primary key and initialises {@code createdAt} / {@code updatedAt}
     * before the entity is inserted for the first time.
     */
    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    /**
     * Refreshes {@code updatedAt} to the current instant on every update.
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
