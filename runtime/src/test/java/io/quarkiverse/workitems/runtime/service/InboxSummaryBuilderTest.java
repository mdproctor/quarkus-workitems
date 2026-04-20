package io.quarkiverse.workitems.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemPriority;
import io.quarkiverse.workitems.runtime.model.WorkItemStatus;

/**
 * Pure unit tests for inbox summary aggregation logic — no Quarkus, no DB.
 */
class InboxSummaryBuilderTest {

    // ── byStatus counts ───────────────────────────────────────────────────────

    @Test
    void byStatus_countsCorrectly() {
        final var items = List.of(
                wi(WorkItemStatus.PENDING, WorkItemPriority.NORMAL, null, null),
                wi(WorkItemStatus.PENDING, WorkItemPriority.HIGH, null, null),
                wi(WorkItemStatus.ASSIGNED, WorkItemPriority.NORMAL, null, null),
                wi(WorkItemStatus.IN_PROGRESS, WorkItemPriority.CRITICAL, null, null));

        final var summary = InboxSummaryBuilder.build(items, Instant.now());

        assertThat(summary.total()).isEqualTo(4);
        assertThat(summary.byStatus()).containsEntry("PENDING", 2L);
        assertThat(summary.byStatus()).containsEntry("ASSIGNED", 1L);
        assertThat(summary.byStatus()).containsEntry("IN_PROGRESS", 1L);
    }

    // ── byPriority counts ─────────────────────────────────────────────────────

    @Test
    void byPriority_countsCorrectly() {
        final var items = List.of(
                wi(WorkItemStatus.PENDING, WorkItemPriority.CRITICAL, null, null),
                wi(WorkItemStatus.PENDING, WorkItemPriority.HIGH, null, null),
                wi(WorkItemStatus.PENDING, WorkItemPriority.HIGH, null, null),
                wi(WorkItemStatus.PENDING, WorkItemPriority.NORMAL, null, null));

        final var summary = InboxSummaryBuilder.build(items, Instant.now());

        assertThat(summary.byPriority()).containsEntry("CRITICAL", 1L);
        assertThat(summary.byPriority()).containsEntry("HIGH", 2L);
        assertThat(summary.byPriority()).containsEntry("NORMAL", 1L);
    }

    // ── overdue count ─────────────────────────────────────────────────────────

    @Test
    void overdue_countsItemsPastExpiresAt() {
        final Instant now = Instant.now();
        final var items = List.of(
                wi(WorkItemStatus.PENDING, WorkItemPriority.NORMAL, now.minusSeconds(3600), null), // overdue
                wi(WorkItemStatus.IN_PROGRESS, WorkItemPriority.NORMAL, now.minusSeconds(1), null), // overdue
                wi(WorkItemStatus.PENDING, WorkItemPriority.NORMAL, now.plusSeconds(3600), null), // not overdue
                wi(WorkItemStatus.PENDING, WorkItemPriority.NORMAL, null, null)); // no deadline

        final var summary = InboxSummaryBuilder.build(items, now);

        assertThat(summary.overdue()).isEqualTo(2);
    }

    @Test
    void overdue_excludesTerminalStatuses() {
        final Instant now = Instant.now();
        final var items = List.of(
                wi(WorkItemStatus.COMPLETED, WorkItemPriority.NORMAL, now.minusSeconds(3600), null),
                wi(WorkItemStatus.REJECTED, WorkItemPriority.NORMAL, now.minusSeconds(3600), null));

        final var summary = InboxSummaryBuilder.build(items, now);

        assertThat(summary.overdue()).isEqualTo(0);
    }

    // ── claimDeadlineBreached count ───────────────────────────────────────────

    @Test
    void claimDeadlineBreached_countsPendingItemsPastClaimDeadline() {
        final Instant now = Instant.now();
        final var items = List.of(
                wi(WorkItemStatus.PENDING, WorkItemPriority.NORMAL, null, now.minusSeconds(3600)), // breached
                wi(WorkItemStatus.PENDING, WorkItemPriority.NORMAL, null, now.plusSeconds(3600)), // not breached
                wi(WorkItemStatus.ASSIGNED, WorkItemPriority.NORMAL, null, now.minusSeconds(3600)), // ASSIGNED — doesn't count
                wi(WorkItemStatus.PENDING, WorkItemPriority.NORMAL, null, null)); // no deadline

        final var summary = InboxSummaryBuilder.build(items, now);

        assertThat(summary.claimDeadlineBreached()).isEqualTo(1);
    }

    // ── empty list ────────────────────────────────────────────────────────────

    @Test
    void emptyList_returnsZeroCounts() {
        final var summary = InboxSummaryBuilder.build(List.of(), Instant.now());

        assertThat(summary.total()).isEqualTo(0);
        assertThat(summary.byStatus()).isEmpty();
        assertThat(summary.byPriority()).isEmpty();
        assertThat(summary.overdue()).isEqualTo(0);
        assertThat(summary.claimDeadlineBreached()).isEqualTo(0);
    }

    // ── null priority handled ─────────────────────────────────────────────────

    @Test
    void nullPriority_notCountedInByPriority() {
        final var items = List.of(
                wi(WorkItemStatus.PENDING, null, null, null));

        final var summary = InboxSummaryBuilder.build(items, Instant.now());

        assertThat(summary.total()).isEqualTo(1);
        assertThat(summary.byPriority()).isEmpty();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private WorkItem wi(final WorkItemStatus status, final WorkItemPriority priority,
            final Instant expiresAt, final Instant claimDeadline) {
        final WorkItem wi = new WorkItem();
        wi.id = UUID.randomUUID();
        wi.status = status;
        wi.priority = priority;
        wi.expiresAt = expiresAt;
        wi.claimDeadline = claimDeadline;
        return wi;
    }
}
