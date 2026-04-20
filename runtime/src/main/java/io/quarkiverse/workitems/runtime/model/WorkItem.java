package io.quarkiverse.workitems.runtime.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

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
public class WorkItem extends PanacheEntityBase {

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

    // -------------------------------------------------------------------------
    // Core descriptive fields
    // -------------------------------------------------------------------------

    /** Short human-readable title. */
    public String title;

    /** Detailed description of what needs to be done. */
    public String description;

    /** Logical category or process classification (e.g. "approval", "review"). */
    public String category;

    /** Key identifying the UI form to render for this work item. */
    @Column(name = "form_key")
    public String formKey;

    // -------------------------------------------------------------------------
    // Status and priority
    // -------------------------------------------------------------------------

    /** Current lifecycle status of this work item. */
    @Enumerated(EnumType.STRING)
    public WorkItemStatus status;

    /** Priority level driving inbox ordering and escalation thresholds. */
    @Enumerated(EnumType.STRING)
    public WorkItemPriority priority;

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
     * Current delegation state; {@code null} when the item has never been delegated.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "delegation_state")
    public DelegationState delegationState;

    /**
     * JSON-serialised audit trail of delegation hops, enabling chain-of-custody
     * tracking across multiple delegations.
     */
    @Column(name = "delegation_chain")
    public String delegationChain;

    /**
     * Status snapshot saved immediately before a suspend so that resume can
     * restore the correct pre-suspension status.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "prior_status")
    public WorkItemStatus priorStatus;

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

    /** When the item transitioned into {@link WorkItemStatus#ASSIGNED}. */
    @Column(name = "assigned_at")
    public Instant assignedAt;

    /** When the item transitioned into {@link WorkItemStatus#IN_PROGRESS}. */
    @Column(name = "started_at")
    public Instant startedAt;

    /** When the item transitioned into a terminal status. */
    @Column(name = "completed_at")
    public Instant completedAt;

    /** When the item transitioned into {@link WorkItemStatus#SUSPENDED}. */
    @Column(name = "suspended_at")
    public Instant suspendedAt;

    // -------------------------------------------------------------------------
    // Labels
    // -------------------------------------------------------------------------

    /**
     * Labels attached to this WorkItem.
     * {@link LabelPersistence#MANUAL} labels are applied by humans.
     * {@link LabelPersistence#INFERRED} labels are maintained by the filter engine.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "work_item_label", joinColumns = @JoinColumn(name = "work_item_id"))
    public List<WorkItemLabel> labels = new ArrayList<>();

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
