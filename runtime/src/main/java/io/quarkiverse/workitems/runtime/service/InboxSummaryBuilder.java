package io.quarkiverse.workitems.runtime.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemStatus;

/**
 * Pure static aggregation logic for inbox summary counts.
 *
 * <p>
 * Separated from the REST layer so it is unit-testable without CDI or a database.
 * The caller is responsible for loading the WorkItems (with appropriate filtering);
 * this class only aggregates them.
 */
public final class InboxSummaryBuilder {

    private InboxSummaryBuilder() {
    }

    /**
     * Summary of WorkItem counts by status, priority, and deadline state.
     *
     * @param total total WorkItems in scope
     * @param byStatus count per {@link WorkItemStatus} name
     * @param byPriority count per priority name; excludes items with null priority
     * @param overdue non-terminal items whose {@code expiresAt} is before {@code now}
     * @param claimDeadlineBreached PENDING items whose {@code claimDeadline} is before {@code now}
     */
    public record InboxSummary(
            long total,
            Map<String, Long> byStatus,
            Map<String, Long> byPriority,
            long overdue,
            long claimDeadlineBreached) {
    }

    /**
     * Build a summary from a pre-loaded list of WorkItems.
     *
     * @param items the WorkItems to summarise; may be empty
     * @param now the reference instant for overdue and claim-deadline checks
     * @return the aggregate summary
     */
    public static InboxSummary build(final List<WorkItem> items, final Instant now) {
        final long total = items.size();

        final Map<String, Long> byStatus = items.stream()
                .filter(wi -> wi.status != null)
                .collect(Collectors.groupingBy(wi -> wi.status.name(), Collectors.counting()));

        final Map<String, Long> byPriority = items.stream()
                .filter(wi -> wi.priority != null)
                .collect(Collectors.groupingBy(wi -> wi.priority.name(), Collectors.counting()));

        final long overdue = items.stream()
                .filter(wi -> wi.status != null && !wi.status.isTerminal())
                .filter(wi -> wi.expiresAt != null && wi.expiresAt.isBefore(now))
                .count();

        final long claimDeadlineBreached = items.stream()
                .filter(wi -> wi.status == WorkItemStatus.PENDING)
                .filter(wi -> wi.claimDeadline != null && wi.claimDeadline.isBefore(now))
                .count();

        return new InboxSummary(total, byStatus, byPriority, overdue, claimDeadlineBreached);
    }
}
