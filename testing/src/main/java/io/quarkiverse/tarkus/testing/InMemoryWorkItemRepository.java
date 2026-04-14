package io.quarkiverse.tarkus.testing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkiverse.tarkus.runtime.model.WorkItem;
import io.quarkiverse.tarkus.runtime.model.WorkItemPriority;
import io.quarkiverse.tarkus.runtime.model.WorkItemStatus;
import io.quarkiverse.tarkus.runtime.repository.WorkItemRepository;

/**
 * In-memory implementation of {@link WorkItemRepository} for use in tests of
 * applications that embed Quarkus Tarkus. No datasource or Flyway configuration
 * is required.
 *
 * <p>
 * Activate by including {@code quarkus-tarkus-testing} on the test classpath. CDI
 * selects this bean over the default Panache implementation via {@code @Alternative}
 * and {@code @Priority(1)}.
 *
 * <p>
 * <strong>Not thread-safe</strong> — designed for single-threaded test use only.
 *
 * <p>
 * Call {@link #clear()} in a {@code @BeforeEach} method to isolate tests from one
 * another.
 */
@ApplicationScoped
@Alternative
@Priority(1)
public class InMemoryWorkItemRepository implements WorkItemRepository {

    // NOT thread-safe — designed for single-threaded test use
    private final Map<UUID, WorkItem> store = new LinkedHashMap<>();

    /**
     * Clears all stored WorkItems. Call in {@code @BeforeEach} to isolate tests.
     */
    public void clear() {
        store.clear();
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * If {@code workItem.id} is {@code null} a fresh {@link UUID} is assigned before
     * the item is stored.
     */
    @Override
    public WorkItem save(final WorkItem workItem) {
        if (workItem.id == null) {
            workItem.id = UUID.randomUUID();
        }
        store.put(workItem.id, workItem);
        return workItem;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<WorkItem> findById(final UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<WorkItem> findAll() {
        return new ArrayList<>(store.values());
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Assignment visibility is evaluated with OR logic across the three assignment
     * dimensions: {@code assigneeId}, {@code candidateGroups}, and
     * {@code candidateUsers}. Each additional filter (status, priority, category,
     * followUpBefore) is then applied with AND logic.
     */
    @Override
    public List<WorkItem> findInbox(final String assignee, final List<String> candidateGroups,
            final WorkItemStatus status, final WorkItemPriority priority,
            final String category, final Instant followUpBefore) {
        return store.values().stream()
                .filter(wi -> matchesAssignment(wi, assignee, candidateGroups))
                .filter(wi -> status == null || wi.status == status)
                .filter(wi -> priority == null || wi.priority == priority)
                .filter(wi -> category == null || category.equals(wi.category))
                .filter(wi -> followUpBefore == null
                        || (wi.followUpDate != null && !wi.followUpDate.isAfter(followUpBefore)))
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<WorkItem> findExpired(final Instant now) {
        return store.values().stream()
                .filter(wi -> wi.expiresAt != null && !wi.expiresAt.isAfter(now)
                        && (wi.status == WorkItemStatus.PENDING || wi.status == WorkItemStatus.ASSIGNED
                                || wi.status == WorkItemStatus.IN_PROGRESS || wi.status == WorkItemStatus.SUSPENDED))
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<WorkItem> findUnclaimedPastDeadline(final Instant now) {
        return store.values().stream()
                .filter(wi -> wi.claimDeadline != null && !wi.claimDeadline.isAfter(now)
                        && wi.status == WorkItemStatus.PENDING)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the work item is visible to the requesting actor under
     * OR logic across all three assignment dimensions.
     */
    private boolean matchesAssignment(final WorkItem wi, final String assignee,
            final List<String> candidateGroups) {
        if (assignee != null && assignee.equals(wi.assigneeId)) {
            return true;
        }
        if (candidateGroups != null && wi.candidateGroups != null) {
            for (final String group : candidateGroups) {
                if (containsToken(wi.candidateGroups, group)) {
                    return true;
                }
            }
        }
        if (assignee != null && wi.candidateUsers != null && containsToken(wi.candidateUsers, assignee)) {
            return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if {@code csv} contains {@code token} as an exact
     * comma-separated element (after trimming whitespace). Avoids substring
     * false-positives — e.g. {@code "bob"} does NOT match {@code "bobby"}.
     *
     * @param csv comma-separated string to search
     * @param token the exact token to look for
     * @return {@code true} if the token appears as a discrete element
     */
    private boolean containsToken(final String csv, final String token) {
        for (final String element : csv.split(",")) {
            if (element.trim().equals(token)) {
                return true;
            }
        }
        return false;
    }
}
