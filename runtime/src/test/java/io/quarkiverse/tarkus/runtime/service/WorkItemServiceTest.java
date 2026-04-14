package io.quarkiverse.tarkus.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.tarkus.runtime.config.TarkusConfig;
import io.quarkiverse.tarkus.runtime.model.AuditEntry;
import io.quarkiverse.tarkus.runtime.model.DelegationState;
import io.quarkiverse.tarkus.runtime.model.WorkItem;
import io.quarkiverse.tarkus.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.tarkus.runtime.model.WorkItemPriority;
import io.quarkiverse.tarkus.runtime.model.WorkItemStatus;
import io.quarkiverse.tarkus.runtime.repository.AuditEntryRepository;
import io.quarkiverse.tarkus.runtime.repository.WorkItemRepository;

class WorkItemServiceTest {

    // -------------------------------------------------------------------------
    // In-memory repository implementations
    // -------------------------------------------------------------------------

    static class TestWorkItemRepo implements WorkItemRepository {

        private final Map<UUID, WorkItem> store = new ConcurrentHashMap<>();

        @Override
        public WorkItem save(WorkItem workItem) {
            if (workItem.id == null) {
                workItem.id = UUID.randomUUID();
            }
            store.put(workItem.id, workItem);
            return workItem;
        }

        @Override
        public Optional<WorkItem> findById(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<WorkItem> findAll() {
            return new ArrayList<>(store.values());
        }

        @Override
        public List<WorkItem> findInbox(String assignee, List<String> candidateGroups,
                WorkItemStatus status, WorkItemPriority priority,
                String category, Instant followUpBefore) {
            return store.values().stream()
                    .filter(wi -> {
                        // status filter
                        if (status != null && wi.status != status) {
                            return false;
                        }
                        // priority filter
                        if (priority != null && wi.priority != priority) {
                            return false;
                        }
                        // category filter
                        if (category != null && !category.equals(wi.category)) {
                            return false;
                        }
                        // followUpBefore filter
                        if (followUpBefore != null && (wi.followUpDate == null || wi.followUpDate.isAfter(followUpBefore))) {
                            return false;
                        }
                        // assignee or candidateGroups/Users match
                        boolean matchesAssignee = assignee != null && assignee.equals(wi.assigneeId);
                        boolean matchesCandidateGroups = false;
                        if (candidateGroups != null && !candidateGroups.isEmpty() && wi.candidateGroups != null) {
                            for (String g : candidateGroups) {
                                if (wi.candidateGroups.contains(g)) {
                                    matchesCandidateGroups = true;
                                    break;
                                }
                            }
                        }
                        boolean matchesCandidateUsers = assignee != null
                                && wi.candidateUsers != null
                                && wi.candidateUsers.contains(assignee);
                        return matchesAssignee || matchesCandidateGroups || matchesCandidateUsers;
                    })
                    .toList();
        }

        @Override
        public List<WorkItem> findExpired(Instant now) {
            return store.values().stream()
                    .filter(wi -> wi.expiresAt != null && wi.expiresAt.isBefore(now)
                            && wi.status != WorkItemStatus.COMPLETED
                            && wi.status != WorkItemStatus.CANCELLED
                            && wi.status != WorkItemStatus.EXPIRED)
                    .toList();
        }

        @Override
        public List<WorkItem> findUnclaimedPastDeadline(Instant now) {
            return store.values().stream()
                    .filter(wi -> wi.claimDeadline != null && wi.claimDeadline.isBefore(now)
                            && wi.status == WorkItemStatus.PENDING)
                    .toList();
        }
    }

    static class TestAuditRepo implements AuditEntryRepository {

        private final List<AuditEntry> entries = new ArrayList<>();

        @Override
        public void append(AuditEntry entry) {
            entries.add(entry);
        }

        @Override
        public List<AuditEntry> findByWorkItemId(UUID workItemId) {
            return entries.stream()
                    .filter(e -> workItemId.equals(e.workItemId))
                    .toList();
        }
    }

    // -------------------------------------------------------------------------
    // Config helper
    // -------------------------------------------------------------------------

    static TarkusConfig testConfig() {
        return new TarkusConfig() {
            @Override
            public int defaultExpiryHours() {
                return 24;
            }

            @Override
            public int defaultClaimHours() {
                return 4;
            }

            @Override
            public String escalationPolicy() {
                return "notify";
            }

            @Override
            public String claimEscalationPolicy() {
                return "notify";
            }

            @Override
            public CleanupConfig cleanup() {
                return () -> 60;
            }
        };
    }

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    private TestWorkItemRepo repo;
    private TestAuditRepo auditRepo;
    private WorkItemService service;

    @BeforeEach
    void setUp() {
        repo = new TestWorkItemRepo();
        auditRepo = new TestAuditRepo();
        service = new WorkItemService(repo, auditRepo, testConfig());
    }

    private WorkItemCreateRequest basicRequest() {
        return new WorkItemCreateRequest(
                "Test item", "Do something", null, null,
                WorkItemPriority.NORMAL,
                null, null, null, null,
                "system", null, null, null, null);
    }

    // -------------------------------------------------------------------------
    // Happy paths — create
    // -------------------------------------------------------------------------

    @Test
    void create_setsStatusPending() {
        WorkItem wi = service.create(basicRequest());
        assertThat(wi.status).isEqualTo(WorkItemStatus.PENDING);
    }

    @Test
    void create_setsCreatedAt() {
        WorkItem wi = service.create(basicRequest());
        assertThat(wi.createdAt).isNotNull();
    }

    @Test
    void create_setsExpiresAtFromConfigDefault() {
        WorkItem wi = service.create(basicRequest());
        assertThat(wi.expiresAt).isNotNull();
        // expiresAt should be approximately now + 24 h
        Instant expectedApprox = Instant.now().plus(24, ChronoUnit.HOURS);
        assertThat(wi.expiresAt).isAfter(expectedApprox.minus(5, ChronoUnit.MINUTES));
        assertThat(wi.expiresAt).isBefore(expectedApprox.plus(5, ChronoUnit.MINUTES));
    }

    @Test
    void create_storesTitleAndDescription() {
        WorkItem wi = service.create(basicRequest());
        assertThat(wi.title).isEqualTo("Test item");
        assertThat(wi.description).isEqualTo("Do something");
    }

    @Test
    void create_writesCreatedAuditEntry() {
        WorkItem wi = service.create(basicRequest());
        List<AuditEntry> trail = auditRepo.findByWorkItemId(wi.id);
        assertThat(trail).hasSize(1);
        assertThat(trail.get(0).event).isEqualTo("CREATED");
    }

    @Test
    void create_withExplicitExpiresAt_usesProvidedValue() {
        Instant explicit = Instant.now().plus(48, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        WorkItemCreateRequest req = new WorkItemCreateRequest(
                "Explicit expiry", null, null, null,
                WorkItemPriority.NORMAL,
                null, null, null, null,
                "system", null, null, explicit, null);
        WorkItem wi = service.create(req);
        assertThat(wi.expiresAt).isEqualTo(explicit);
    }

    @Test
    void create_withCandidateGroups_storesGroups() {
        WorkItemCreateRequest req = new WorkItemCreateRequest(
                "Group item", null, null, null,
                WorkItemPriority.NORMAL,
                null, "team-a,team-b", null, null,
                "system", null, null, null, null);
        WorkItem wi = service.create(req);
        assertThat(wi.candidateGroups).isEqualTo("team-a,team-b");
    }

    // -------------------------------------------------------------------------
    // Happy paths — claim
    // -------------------------------------------------------------------------

    @Test
    void claim_transitionsPendingToAssigned() {
        WorkItem wi = service.create(basicRequest());
        wi = service.claim(wi.id, "alice");
        assertThat(wi.status).isEqualTo(WorkItemStatus.ASSIGNED);
    }

    @Test
    void claim_setsAssignedAtAndAssigneeId() {
        WorkItem wi = service.create(basicRequest());
        wi = service.claim(wi.id, "alice");
        assertThat(wi.assignedAt).isNotNull();
        assertThat(wi.assigneeId).isEqualTo("alice");
    }

    @Test
    void claim_writesAssignedAuditEntry() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        List<AuditEntry> trail = auditRepo.findByWorkItemId(wi.id);
        assertThat(trail).hasSize(2);
        assertThat(trail.get(1).event).isEqualTo("ASSIGNED");
        assertThat(trail.get(1).actor).isEqualTo("alice");
    }

    // -------------------------------------------------------------------------
    // Happy paths — start
    // -------------------------------------------------------------------------

    @Test
    void start_transitionsAssignedToInProgress() {
        WorkItem wi = service.create(basicRequest());
        wi = service.claim(wi.id, "alice");
        wi = service.start(wi.id, "alice");
        assertThat(wi.status).isEqualTo(WorkItemStatus.IN_PROGRESS);
    }

    @Test
    void start_setsStartedAt() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        wi = service.start(wi.id, "alice");
        assertThat(wi.startedAt).isNotNull();
    }

    @Test
    void start_writesStartedAuditEntry() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        List<AuditEntry> trail = auditRepo.findByWorkItemId(wi.id);
        assertThat(trail.get(trail.size() - 1).event).isEqualTo("STARTED");
    }

    // -------------------------------------------------------------------------
    // Happy paths — complete
    // -------------------------------------------------------------------------

    @Test
    void complete_transitionsInProgressToCompleted() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        wi = service.complete(wi.id, "alice", "Done");
        assertThat(wi.status).isEqualTo(WorkItemStatus.COMPLETED);
    }

    @Test
    void complete_setsCompletedAtAndResolution() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        wi = service.complete(wi.id, "alice", "Resolved successfully");
        assertThat(wi.completedAt).isNotNull();
        assertThat(wi.resolution).isEqualTo("Resolved successfully");
    }

    @Test
    void complete_writesCompletedAuditEntry() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        service.complete(wi.id, "alice", "Done");
        List<AuditEntry> trail = auditRepo.findByWorkItemId(wi.id);
        assertThat(trail.get(trail.size() - 1).event).isEqualTo("COMPLETED");
    }

    // -------------------------------------------------------------------------
    // Happy paths — reject
    // -------------------------------------------------------------------------

    @Test
    void reject_fromInProgress_transitionsToRejected() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        wi = service.reject(wi.id, "alice", "Not my responsibility");
        assertThat(wi.status).isEqualTo(WorkItemStatus.REJECTED);
    }

    @Test
    void reject_fromInProgress_setsCompletedAt() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        wi = service.reject(wi.id, "alice", "Out of scope");
        assertThat(wi.completedAt).isNotNull();
    }

    @Test
    void reject_fromAssigned_isValid() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        wi = service.reject(wi.id, "alice", "Wrong team");
        assertThat(wi.status).isEqualTo(WorkItemStatus.REJECTED);
    }

    @Test
    void reject_writesRejectedAuditEntry() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.reject(wi.id, "alice", "Not applicable");
        List<AuditEntry> trail = auditRepo.findByWorkItemId(wi.id);
        assertThat(trail.get(trail.size() - 1).event).isEqualTo("REJECTED");
    }

    // -------------------------------------------------------------------------
    // Happy paths — delegate
    // -------------------------------------------------------------------------

    @Test
    void delegate_firstTime_setsOwnerToActor() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        wi = service.delegate(wi.id, "alice", "bob");
        assertThat(wi.owner).isEqualTo("alice");
    }

    @Test
    void delegate_setsNewAssigneeAndStatusPending() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        wi = service.delegate(wi.id, "alice", "bob");
        assertThat(wi.assigneeId).isEqualTo("bob");
        assertThat(wi.status).isEqualTo(WorkItemStatus.PENDING);
    }

    @Test
    void delegate_setsDelegationStatePending() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        wi = service.delegate(wi.id, "alice", "bob");
        assertThat(wi.delegationState).isEqualTo(DelegationState.PENDING);
    }

    @Test
    void delegate_addsDelegatorToDelegationChain() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        wi = service.delegate(wi.id, "alice", "bob");
        assertThat(wi.delegationChain).contains("alice");
    }

    @Test
    void delegate_writesDelegatedAuditEntry() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.delegate(wi.id, "alice", "bob");
        List<AuditEntry> trail = auditRepo.findByWorkItemId(wi.id);
        assertThat(trail.get(trail.size() - 1).event).isEqualTo("DELEGATED");
    }

    @Test
    void delegate_secondTime_doesNotOverwriteOwner() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        wi = service.delegate(wi.id, "alice", "bob");
        // bob claims and re-delegates
        service.claim(wi.id, "bob");
        wi = service.delegate(wi.id, "bob", "carol");
        assertThat(wi.owner).isEqualTo("alice");
    }

    @Test
    void delegate_secondTime_delegationChainGrows() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        wi = service.delegate(wi.id, "alice", "bob");
        service.claim(wi.id, "bob");
        wi = service.delegate(wi.id, "bob", "carol");
        assertThat(wi.delegationChain).contains("alice");
        assertThat(wi.delegationChain).contains("bob");
    }

    // -------------------------------------------------------------------------
    // Happy paths — release
    // -------------------------------------------------------------------------

    @Test
    void release_transitionsAssignedToPending() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        wi = service.release(wi.id, "alice");
        assertThat(wi.status).isEqualTo(WorkItemStatus.PENDING);
    }

    @Test
    void release_clearsAssigneeId() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        wi = service.release(wi.id, "alice");
        assertThat(wi.assigneeId).isNull();
    }

    @Test
    void release_writesReleasedAuditEntry() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.release(wi.id, "alice");
        List<AuditEntry> trail = auditRepo.findByWorkItemId(wi.id);
        assertThat(trail.get(trail.size() - 1).event).isEqualTo("RELEASED");
    }

    // -------------------------------------------------------------------------
    // Happy paths — suspend
    // -------------------------------------------------------------------------

    @Test
    void suspend_fromAssigned_transitionsToSuspended() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        wi = service.suspend(wi.id, "alice", "waiting for input");
        assertThat(wi.status).isEqualTo(WorkItemStatus.SUSPENDED);
    }

    @Test
    void suspend_fromAssigned_setsSuspendedAtAndPriorStatus() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        wi = service.suspend(wi.id, "alice", "blocked");
        assertThat(wi.suspendedAt).isNotNull();
        assertThat(wi.priorStatus).isEqualTo(WorkItemStatus.ASSIGNED);
    }

    @Test
    void suspend_fromInProgress_setsPriorStatusInProgress() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        wi = service.suspend(wi.id, "alice", "waiting");
        assertThat(wi.status).isEqualTo(WorkItemStatus.SUSPENDED);
        assertThat(wi.priorStatus).isEqualTo(WorkItemStatus.IN_PROGRESS);
    }

    @Test
    void suspend_writesSuspendedAuditEntry() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.suspend(wi.id, "alice", "blocked");
        List<AuditEntry> trail = auditRepo.findByWorkItemId(wi.id);
        assertThat(trail.get(trail.size() - 1).event).isEqualTo("SUSPENDED");
    }

    // -------------------------------------------------------------------------
    // Happy paths — resume
    // -------------------------------------------------------------------------

    @Test
    void resume_afterAssignedSuspend_returnsToAssigned() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.suspend(wi.id, "alice", "wait");
        wi = service.resume(wi.id, "alice");
        assertThat(wi.status).isEqualTo(WorkItemStatus.ASSIGNED);
    }

    @Test
    void resume_clearsSuspendedAt() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.suspend(wi.id, "alice", "wait");
        wi = service.resume(wi.id, "alice");
        assertThat(wi.suspendedAt).isNull();
    }

    @Test
    void resume_writesResumedAuditEntry() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.suspend(wi.id, "alice", "wait");
        service.resume(wi.id, "alice");
        List<AuditEntry> trail = auditRepo.findByWorkItemId(wi.id);
        assertThat(trail.get(trail.size() - 1).event).isEqualTo("RESUMED");
    }

    @Test
    void resume_afterInProgressSuspend_returnsToInProgress() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        service.suspend(wi.id, "alice", "wait");
        wi = service.resume(wi.id, "alice");
        assertThat(wi.status).isEqualTo(WorkItemStatus.IN_PROGRESS);
    }

    // -------------------------------------------------------------------------
    // Happy paths — cancel
    // -------------------------------------------------------------------------

    @Test
    void cancel_fromPending_transitionsToCancelled() {
        WorkItem wi = service.create(basicRequest());
        wi = service.cancel(wi.id, "admin", "no longer needed");
        assertThat(wi.status).isEqualTo(WorkItemStatus.CANCELLED);
    }

    @Test
    void cancel_fromPending_setsCompletedAt() {
        WorkItem wi = service.create(basicRequest());
        wi = service.cancel(wi.id, "admin", "obsolete");
        assertThat(wi.completedAt).isNotNull();
    }

    @Test
    void cancel_writesCancelledAuditEntry() {
        WorkItem wi = service.create(basicRequest());
        service.cancel(wi.id, "admin", "no longer needed");
        List<AuditEntry> trail = auditRepo.findByWorkItemId(wi.id);
        assertThat(trail.get(trail.size() - 1).event).isEqualTo("CANCELLED");
    }

    @Test
    void cancel_fromAssigned_transitionsToCancelled() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        wi = service.cancel(wi.id, "admin", "revoked");
        assertThat(wi.status).isEqualTo(WorkItemStatus.CANCELLED);
    }

    @Test
    void cancel_fromInProgress_transitionsToCancelled() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        wi = service.cancel(wi.id, "admin", "project cancelled");
        assertThat(wi.status).isEqualTo(WorkItemStatus.CANCELLED);
    }

    @Test
    void cancel_fromSuspended_transitionsToCancelled() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.suspend(wi.id, "alice", "blocked");
        wi = service.cancel(wi.id, "admin", "giving up");
        assertThat(wi.status).isEqualTo(WorkItemStatus.CANCELLED);
    }

    // -------------------------------------------------------------------------
    // Invalid transitions — expect IllegalStateException
    // -------------------------------------------------------------------------

    @Test
    void complete_pendingItem_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        assertThatThrownBy(() -> service.complete(wi.id, "alice", "done"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void complete_completedItem_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        service.complete(wi.id, "alice", "done");
        assertThatThrownBy(() -> service.complete(wi.id, "alice", "done again"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void start_pendingItem_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        assertThatThrownBy(() -> service.start(wi.id, "alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void claim_assignedItem_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        assertThatThrownBy(() -> service.claim(wi.id, "bob"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void release_pendingItem_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        assertThatThrownBy(() -> service.release(wi.id, "alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void suspend_completedItem_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        service.complete(wi.id, "alice", "done");
        assertThatThrownBy(() -> service.suspend(wi.id, "alice", "wait"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resume_inProgressItem_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        assertThatThrownBy(() -> service.resume(wi.id, "alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // Not found — expect WorkItemNotFoundException
    // -------------------------------------------------------------------------

    @Test
    void claim_nonExistentUuid_throwsWorkItemNotFoundException() {
        UUID nonExistent = UUID.randomUUID();
        assertThatThrownBy(() -> service.claim(nonExistent, "alice"))
                .isInstanceOf(WorkItemNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // Audit trail integrity
    // -------------------------------------------------------------------------

    @Test
    void auditTrail_afterFullHappyPath_hasFourEntriesInOrder() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        service.complete(wi.id, "alice", "done");

        List<AuditEntry> trail = auditRepo.findByWorkItemId(wi.id);
        assertThat(trail).hasSize(4);
        assertThat(trail.get(0).event).isEqualTo("CREATED");
        assertThat(trail.get(1).event).isEqualTo("ASSIGNED");
        assertThat(trail.get(2).event).isEqualTo("STARTED");
        assertThat(trail.get(3).event).isEqualTo("COMPLETED");
    }

    @Test
    void auditTrail_actorIsRecordedCorrectly() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");

        List<AuditEntry> trail = auditRepo.findByWorkItemId(wi.id);
        // CREATED actor comes from createdBy
        assertThat(trail.get(0).actor).isEqualTo("system");
        assertThat(trail.get(1).actor).isEqualTo("alice");
    }

    // -------------------------------------------------------------------------
    // Inbox query
    // -------------------------------------------------------------------------

    @Test
    void inbox_findsByAssignee() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");

        List<WorkItem> inbox = repo.findInbox("alice", null, WorkItemStatus.ASSIGNED, null, null, null);
        assertThat(inbox).extracting(w -> w.id).contains(wi.id);
    }

    @Test
    void inbox_findsByCandidateGroup() {
        WorkItemCreateRequest req = new WorkItemCreateRequest(
                "Group task", null, null, null,
                WorkItemPriority.NORMAL,
                null, "team-a,team-b", null, null,
                "system", null, null, null, null);
        WorkItem wi = service.create(req);

        List<WorkItem> inbox = repo.findInbox(null, List.of("team-a"), null, null, null, null);
        assertThat(inbox).extracting(w -> w.id).contains(wi.id);
    }

    @Test
    void inbox_findsByCandidateUsers() {
        WorkItemCreateRequest req = new WorkItemCreateRequest(
                "Candidate task", null, null, null,
                WorkItemPriority.NORMAL,
                null, null, "bob", null,
                "system", null, null, null, null);
        WorkItem wi = service.create(req);

        List<WorkItem> inbox = repo.findInbox("bob", null, null, null, null, null);
        assertThat(inbox).extracting(w -> w.id).contains(wi.id);
    }

    @Test
    void inbox_completedItemNotReturnedWhenFilteringByPending() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        service.complete(wi.id, "alice", "done");

        List<WorkItem> inbox = repo.findInbox("alice", null, WorkItemStatus.PENDING, null, null, null);
        assertThat(inbox).extracting(w -> w.id).doesNotContain(wi.id);
    }
}
