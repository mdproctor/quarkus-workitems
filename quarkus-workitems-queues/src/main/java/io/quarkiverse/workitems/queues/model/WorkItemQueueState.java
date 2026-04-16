package io.quarkiverse.workitems.queues.model;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/** Soft-assignment state for a WorkItem within the queue subsystem. */
@Entity
@Table(name = "work_item_queue_state")
public class WorkItemQueueState extends PanacheEntityBase {

    /** Primary key — matches the WorkItem UUID; no auto-generation. */
    @Id
    @Column(name = "work_item_id")
    public UUID workItemId;

    /**
     * When {@code true}, the assigned user has indicated they are willing to release
     * this WorkItem back to the queue so another actor may claim it.
     */
    public boolean relinquishable = false;

    /**
     * Return the existing {@link WorkItemQueueState} for the given work item, or create and
     * persist a new one with defaults if none exists.
     *
     * @param workItemId the UUID of the WorkItem whose queue state is needed
     * @return the existing or newly created state record
     */
    public static WorkItemQueueState findOrCreate(final UUID workItemId) {
        return WorkItemQueueState.<WorkItemQueueState> findByIdOptional(workItemId)
                .orElseGet(() -> {
                    final var s = new WorkItemQueueState();
                    s.workItemId = workItemId;
                    s.persist();
                    return s;
                });
    }
}
