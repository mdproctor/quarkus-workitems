package io.quarkiverse.work.runtime.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * A predefined blueprint for creating {@link WorkItem} instances.
 *
 * <h2>What templates are for</h2>
 * <p>
 * Many WorkItems follow repeatable patterns: every loan application needs the same
 * category, candidateGroups, expiry window, and payload structure. Every security
 * incident triage follows the same priority and routing. Templates capture these
 * patterns once, then each instantiation creates a correctly-configured WorkItem
 * in a single API call rather than repeating a 15-field body.
 *
 * <h2>Instantiation</h2>
 * <p>
 * {@code POST /workitem-templates/{id}/instantiate} creates a new PENDING WorkItem
 * from this template. The caller may override:
 * <ul>
 * <li>{@code title} — defaults to {@link #name} if not provided</li>
 * <li>{@code assigneeId} — for direct assignment at creation time</li>
 * <li>{@code createdBy} — who or what triggered the instantiation</li>
 * </ul>
 * All other fields ({@link #category}, {@link #priority}, {@link #candidateGroups},
 * etc.) are copied from the template without override.
 *
 * <h2>Labels</h2>
 * <p>
 * {@link #labelPaths} is a JSON array of label path strings:
 * {@code ["intake/triage", "priority/high"]}. At instantiation, these are applied
 * as {@link LabelPersistence#MANUAL} labels (not INFERRED — the filter engine may
 * add INFERRED labels on top after the first lifecycle event fires).
 */
@Entity
@Table(name = "work_item_template")
public class WorkItemTemplate extends PanacheEntityBase {

    /** Primary key — UUID assigned on first persist. */
    @Id
    public UUID id;

    /** Human-readable name; used as the WorkItem title if no override is provided. */
    @Column(nullable = false, length = 255)
    public String name;

    /** Optional description of what this template is for. */
    @Column(columnDefinition = "TEXT")
    public String description;

    /** Default category copied to every instantiated WorkItem. */
    @Column(length = 255)
    public String category;

    /** Default priority; null means the WorkItem will use the system default (NORMAL). */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    public WorkItemPriority priority;

    /** Default comma-separated candidate groups for queue routing. */
    @Column(name = "candidate_groups", length = 500)
    public String candidateGroups;

    /** Default comma-separated candidate users. */
    @Column(name = "candidate_users", length = 500)
    public String candidateUsers;

    /** Default comma-separated required capabilities. */
    @Column(name = "required_capabilities", length = 500)
    public String requiredCapabilities;

    /**
     * Default completion deadline in hours. {@code null} → system default
     * ({@code quarkus.work.default-expiry-hours}).
     */
    @Column(name = "default_expiry_hours")
    public Integer defaultExpiryHours;

    /**
     * Default claim deadline in hours. {@code null} → system default
     * ({@code quarkus.work.default-claim-hours}).
     */
    @Column(name = "default_claim_hours")
    public Integer defaultClaimHours;

    /**
     * Default completion deadline in <em>business hours</em>. When set, takes precedence
     * over {@link #defaultExpiryHours} and is resolved via {@code BusinessCalendar}
     * at WorkItem creation time.
     */
    @Column(name = "default_expiry_business_hours")
    public Integer defaultExpiryBusinessHours;

    /**
     * Default claim deadline in <em>business hours</em>. When set, takes precedence
     * over {@link #defaultClaimHours} and is resolved via {@code BusinessCalendar}
     * at WorkItem creation time.
     */
    @Column(name = "default_claim_business_hours")
    public Integer defaultClaimBusinessHours;

    /**
     * Default JSON payload copied to every instantiated WorkItem.
     * May contain domain-specific context pre-filled for the process.
     */
    @Column(name = "default_payload", columnDefinition = "TEXT")
    public String defaultPayload;

    /**
     * JSON array of label paths applied as MANUAL labels at instantiation.
     * Example: {@code ["intake/triage", "priority/high"]}
     * Stored as a JSON string; parsed by the service at instantiation time.
     */
    @Column(name = "label_paths", columnDefinition = "TEXT")
    public String labelPaths;

    /** Who created this template. */
    @Column(name = "created_by", nullable = false, length = 255)
    public String createdBy;

    /** When this template was created. */
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Return all templates, ordered by name ascending. */
    public static List<WorkItemTemplate> listAllByName() {
        return list("ORDER BY name ASC");
    }
}
