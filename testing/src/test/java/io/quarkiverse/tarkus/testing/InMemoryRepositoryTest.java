package io.quarkiverse.tarkus.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.tarkus.runtime.model.AuditEntry;
import io.quarkiverse.tarkus.runtime.model.WorkItem;
import io.quarkiverse.tarkus.runtime.model.WorkItemPriority;
import io.quarkiverse.tarkus.runtime.model.WorkItemStatus;

/**
 * Pure JUnit 5 tests for the in-memory repository implementations.
 *
 * <p>
 * No {@code @QuarkusTest} — the implementations are plain Java objects and can be
 * constructed and exercised without a CDI container.
 */
class InMemoryRepositoryTest {

    private InMemoryWorkItemRepository workItemRepo;
    private InMemoryAuditEntryRepository auditRepo;

    @BeforeEach
    void setUp() {
        workItemRepo = new InMemoryWorkItemRepository();
        auditRepo = new InMemoryAuditEntryRepository();
    }

    // =========================================================================
    // WorkItemRepository — basic CRUD
    // =========================================================================

    @Test
    void save_assignsUuidIfAbsent() {
        final WorkItem wi = workItem(WorkItemStatus.PENDING);
        assertThat(wi.id).isNull();

        workItemRepo.save(wi);

        assertThat(wi.id).isNotNull();
    }

    @Test
    void save_returnsPersistedItem() {
        final WorkItem wi = workItem(WorkItemStatus.PENDING);
        workItemRepo.save(wi);

        assertThat(workItemRepo.findById(wi.id)).isPresent().hasValue(wi);
    }

    @Test
    void findById_absent_returnsEmpty() {
        assertThat(workItemRepo.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findAll_returnsAllSaved() {
        workItemRepo.save(workItem(WorkItemStatus.PENDING));
        workItemRepo.save(workItem(WorkItemStatus.ASSIGNED));
        workItemRepo.save(workItem(WorkItemStatus.IN_PROGRESS));

        assertThat(workItemRepo.findAll()).hasSize(3);
    }

    @Test
    void clear_removesAll() {
        workItemRepo.save(workItem(WorkItemStatus.PENDING));
        workItemRepo.save(workItem(WorkItemStatus.ASSIGNED));

        workItemRepo.clear();

        assertThat(workItemRepo.findAll()).isEmpty();
    }

    // =========================================================================
    // WorkItemRepository — findInbox assignment filters
    // =========================================================================

    @Test
    void findInbox_byAssignee() {
        final WorkItem wi = workItem(WorkItemStatus.ASSIGNED);
        wi.assigneeId = "alice";
        workItemRepo.save(wi);

        final List<WorkItem> result = workItemRepo.findInbox("alice", null, null, null, null, null);

        assertThat(result).containsExactly(wi);
    }

    @Test
    void findInbox_byCandidateGroup() {
        final WorkItem wi = workItem(WorkItemStatus.PENDING);
        wi.candidateGroups = "team-a,team-b";
        workItemRepo.save(wi);

        final List<WorkItem> result = workItemRepo.findInbox(null, List.of("team-a"), null, null, null, null);

        assertThat(result).containsExactly(wi);
    }

    @Test
    void findInbox_byCandidateUser_exactMatch() {
        final WorkItem wi = workItem(WorkItemStatus.PENDING);
        wi.candidateUsers = "bob";
        workItemRepo.save(wi);

        final List<WorkItem> result = workItemRepo.findInbox("bob", null, null, null, null, null);

        assertThat(result).containsExactly(wi);
    }

    @Test
    void findInbox_candidateUser_noPartialMatch() {
        final WorkItem wi = workItem(WorkItemStatus.PENDING);
        wi.candidateUsers = "bobby";
        workItemRepo.save(wi);

        // "bob" must NOT match "bobby" — token matching, not substring
        final List<WorkItem> result = workItemRepo.findInbox("bob", null, null, null, null, null);

        assertThat(result).isEmpty();
    }

    // =========================================================================
    // WorkItemRepository — findInbox additional filters
    // =========================================================================

    @Test
    void findInbox_statusFilter() {
        final WorkItem wi = workItem(WorkItemStatus.COMPLETED);
        wi.assigneeId = "alice";
        workItemRepo.save(wi);

        final List<WorkItem> result = workItemRepo.findInbox("alice", null, WorkItemStatus.PENDING, null, null, null);

        assertThat(result).isEmpty();
    }

    @Test
    void findInbox_categoryFilter() {
        final WorkItem wi = workItem(WorkItemStatus.PENDING);
        wi.assigneeId = "alice";
        wi.category = "finance";
        workItemRepo.save(wi);

        final List<WorkItem> result = workItemRepo.findInbox("alice", null, null, null, "legal", null);

        assertThat(result).isEmpty();
    }

    // =========================================================================
    // WorkItemRepository — expiry and deadline queries
    // =========================================================================

    @Test
    void findExpired() {
        final Instant fiveMinutesAgo = Instant.now().minus(5, ChronoUnit.MINUTES);

        final WorkItem expired = workItem(WorkItemStatus.PENDING);
        expired.expiresAt = fiveMinutesAgo;
        workItemRepo.save(expired);

        final WorkItem alreadyCompleted = workItem(WorkItemStatus.COMPLETED);
        alreadyCompleted.expiresAt = fiveMinutesAgo;
        workItemRepo.save(alreadyCompleted);

        final WorkItem notExpired = workItem(WorkItemStatus.PENDING);
        notExpired.expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
        workItemRepo.save(notExpired);

        final List<WorkItem> result = workItemRepo.findExpired(Instant.now());

        assertThat(result).containsExactly(expired);
    }

    @Test
    void findUnclaimedPastDeadline() {
        final Instant fiveMinutesAgo = Instant.now().minus(5, ChronoUnit.MINUTES);

        final WorkItem unclaimed = workItem(WorkItemStatus.PENDING);
        unclaimed.claimDeadline = fiveMinutesAgo;
        workItemRepo.save(unclaimed);

        final WorkItem assigned = workItem(WorkItemStatus.ASSIGNED);
        assigned.claimDeadline = fiveMinutesAgo;
        workItemRepo.save(assigned);

        final WorkItem futureDeadline = workItem(WorkItemStatus.PENDING);
        futureDeadline.claimDeadline = Instant.now().plus(1, ChronoUnit.HOURS);
        workItemRepo.save(futureDeadline);

        final List<WorkItem> result = workItemRepo.findUnclaimedPastDeadline(Instant.now());

        assertThat(result).containsExactly(unclaimed);
    }

    // =========================================================================
    // AuditEntryRepository
    // =========================================================================

    @Test
    void append_assignsUuidAndOccurredAt() {
        final AuditEntry entry = auditEntry(UUID.randomUUID(), "CREATED");
        entry.id = null;
        entry.occurredAt = null;

        auditRepo.append(entry);

        assertThat(entry.id).isNotNull();
        assertThat(entry.occurredAt).isNotNull();
    }

    @Test
    void findByWorkItemId_returnsOnlyMatchingEntries() {
        final UUID workItemId1 = UUID.randomUUID();
        final UUID workItemId2 = UUID.randomUUID();

        auditRepo.append(auditEntry(workItemId1, "CREATED"));
        auditRepo.append(auditEntry(workItemId1, "ASSIGNED"));
        auditRepo.append(auditEntry(workItemId2, "CREATED"));

        final List<AuditEntry> result = auditRepo.findByWorkItemId(workItemId1);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(e -> workItemId1.equals(e.workItemId));
    }

    @Test
    void findByWorkItemId_orderedByOccurredAt() {
        final UUID workItemId = UUID.randomUUID();

        final Instant t1 = Instant.now().minus(10, ChronoUnit.MINUTES);
        final Instant t2 = Instant.now().minus(5, ChronoUnit.MINUTES);
        final Instant t3 = Instant.now();

        // Append in reverse chronological order
        final AuditEntry third = auditEntry(workItemId, "COMPLETED");
        third.occurredAt = t3;
        auditRepo.append(third);

        final AuditEntry second = auditEntry(workItemId, "ASSIGNED");
        second.occurredAt = t2;
        auditRepo.append(second);

        final AuditEntry first = auditEntry(workItemId, "CREATED");
        first.occurredAt = t1;
        auditRepo.append(first);

        final List<AuditEntry> result = auditRepo.findByWorkItemId(workItemId);

        assertThat(result).extracting(e -> e.event)
                .containsExactly("CREATED", "ASSIGNED", "COMPLETED");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private WorkItem workItem(final WorkItemStatus status) {
        final WorkItem wi = new WorkItem();
        wi.status = status;
        wi.priority = WorkItemPriority.NORMAL;
        wi.title = "Test";
        wi.createdAt = Instant.now();
        wi.updatedAt = Instant.now();
        return wi;
    }

    private AuditEntry auditEntry(final UUID workItemId, final String event) {
        final AuditEntry entry = new AuditEntry();
        entry.workItemId = workItemId;
        entry.event = event;
        entry.occurredAt = Instant.now();
        return entry;
    }
}
