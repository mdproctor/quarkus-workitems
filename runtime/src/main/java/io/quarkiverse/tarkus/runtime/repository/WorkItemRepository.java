package io.quarkiverse.tarkus.runtime.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.quarkiverse.tarkus.runtime.model.WorkItem;
import io.quarkiverse.tarkus.runtime.model.WorkItemPriority;
import io.quarkiverse.tarkus.runtime.model.WorkItemStatus;

/**
 * Repository SPI for persisting and querying {@link WorkItem} entities.
 *
 * <p>
 * The default implementation uses Hibernate ORM with Panache. Alternative
 * implementations (e.g. in-memory for testing) can be substituted via CDI.
 */
public interface WorkItemRepository {

    /**
     * Persist a new or updated WorkItem and return the saved instance.
     *
     * @param workItem the work item to save; must not be {@code null}
     * @return the persisted work item (same instance, post-{@code @PrePersist} / {@code @PreUpdate})
     */
    WorkItem save(WorkItem workItem);

    /**
     * Find a WorkItem by its primary key.
     *
     * @param id the UUID primary key to look up
     * @return an {@link Optional} containing the work item if found, or empty if not
     */
    Optional<WorkItem> findById(UUID id);

    /**
     * Return all WorkItems — for admin and monitoring use only.
     *
     * <p>
     * Do not use in user-facing inbox queries; use {@link #findInbox} instead.
     *
     * @return unordered list of all persisted work items
     */
    List<WorkItem> findAll();

    /**
     * Inbox query: returns WorkItems visible to the given actor.
     *
     * <p>
     * Visibility rules (applied with OR logic across the assignment dimensions):
     * <ul>
     * <li>{@code assigneeId} equals {@code assignee}; OR</li>
     * <li>{@code candidateGroups} contains any group in {@code candidateGroups}; OR</li>
     * <li>{@code candidateUsers} contains {@code assignee}.</li>
     * </ul>
     *
     * <p>
     * All filter parameters are nullable — a {@code null} value means "no filter on
     * this dimension". Filters are combined with AND logic: only items that satisfy
     * every non-null filter are returned.
     *
     * @param assignee identity of the requesting actor; may be {@code null}
     * @param candidateGroups groups the actor belongs to; may be {@code null} or empty
     * @param status if non-null, only items in this status are returned
     * @param priority if non-null, only items with this priority are returned
     * @param category if non-null, only items in this category are returned
     * @param followUpBefore if non-null, only items whose {@code followUpDate} is on or
     *        before this instant are returned
     * @return list of matching work items, ordered by priority descending then creation time ascending
     */
    List<WorkItem> findInbox(String assignee, List<String> candidateGroups,
            WorkItemStatus status, WorkItemPriority priority,
            String category, Instant followUpBefore);

    /**
     * Find WorkItems whose {@code expiresAt} is on or before {@code now} and whose
     * status is one of {@link WorkItemStatus#PENDING}, {@link WorkItemStatus#ASSIGNED},
     * {@link WorkItemStatus#IN_PROGRESS}, or {@link WorkItemStatus#SUSPENDED}.
     *
     * <p>
     * Used by the expiry cleanup job to identify items that have breached their
     * completion deadline and require escalation.
     *
     * @param now the reference instant to compare against {@code expiresAt}
     * @return list of work items that have expired and are still active
     */
    List<WorkItem> findExpired(Instant now);

    /**
     * Find WorkItems whose {@code claimDeadline} is on or before {@code now} and
     * whose status is {@link WorkItemStatus#PENDING}.
     *
     * <p>
     * Used by the expiry cleanup job to identify unclaimed items that have
     * breached their claim deadline and require claim-escalation policy enforcement.
     *
     * @param now the reference instant to compare against {@code claimDeadline}
     * @return list of pending work items that have passed their claim deadline
     */
    List<WorkItem> findUnclaimedPastDeadline(Instant now);
}
