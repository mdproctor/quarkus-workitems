package io.quarkiverse.workitems.queues.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/** A saved queue view defined by a label pattern, scope, and sort configuration. */
@Entity
@Table(name = "queue_view")
public class QueueView extends PanacheEntityBase {

    /** Primary key — generated on first persist. */
    @Id
    public UUID id;

    /** Human-readable name for this queue view. */
    @Column(nullable = false, length = 255)
    public String name;

    /** Label pattern used to populate the queue (supports {@code *} and {@code **} wildcards). */
    @Column(name = "label_pattern", nullable = false, length = 500)
    public String labelPattern;

    /** Visibility scope of this queue view. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public FilterScope scope;

    /** Owner identity for PERSONAL-scoped views; null for TEAM/ORG. */
    @Column(name = "owner_id", length = 255)
    public String ownerId;

    /** Optional additional filter conditions applied on top of the label pattern. */
    @Column(name = "additional_conditions", length = 2000)
    public String additionalConditions;

    /** Field name used to sort queue results (default: {@code createdAt}). */
    @Column(name = "sort_field", length = 50)
    public String sortField = "createdAt";

    /** Sort direction — {@code ASC} or {@code DESC} (default: {@code ASC}). */
    @Column(name = "sort_direction", length = 4)
    public String sortDirection = "ASC";

    /** Instant at which this queue view was created. */
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /** Assigns a UUID and records creation time before the entity is first persisted. */
    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = Instant.now();
    }
}
