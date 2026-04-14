package io.quarkiverse.tarkus.runtime.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Immutable audit log entry recording a lifecycle event on a {@link WorkItem}.
 *
 * <p>
 * Entries are append-only: once written they are never updated or deleted.
 * The full audit trail for a work item can be retrieved via
 * {@code AuditEntryRepository.findByWorkItemId}.
 */
@Entity
@Table(name = "audit_entry")
public class AuditEntry extends PanacheEntityBase {

    /** Primary key — UUID assigned on first persist. */
    @Id
    public UUID id;

    /** Foreign key referencing the {@link WorkItem} this entry belongs to. */
    @Column(name = "work_item_id", nullable = false)
    public UUID workItemId;

    /**
     * Short event identifier describing what happened
     * (e.g. {@code "CREATED"}, {@code "ASSIGNED"}, {@code "COMPLETED"}).
     */
    @Column(nullable = false)
    public String event;

    /** Identity of the actor (user or system) that triggered the event. */
    public String actor;

    /**
     * Optional free-text or JSON detail providing additional context about the event
     * (e.g. delegation target, rejection reason, escalation policy applied).
     */
    @Column(columnDefinition = "TEXT")
    public String detail;

    /** Instant at which this event occurred. */
    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;

    /**
     * Assigns a UUID primary key and sets {@code occurredAt} to the current instant
     * if not already supplied before the entity is inserted.
     */
    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }
}
