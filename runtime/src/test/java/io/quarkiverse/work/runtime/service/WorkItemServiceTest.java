package io.quarkiverse.work.runtime.service;

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

import io.quarkiverse.work.api.AssignmentDecision;
import io.quarkiverse.work.core.strategy.WorkBroker;
import io.quarkiverse.work.runtime.api.WorkItemLabelResponse;
import io.quarkiverse.work.runtime.config.WorkItemsConfig;
import io.quarkiverse.work.runtime.model.AuditEntry;
import io.quarkiverse.work.runtime.model.DelegationState;
import io.quarkiverse.work.runtime.model.LabelPersistence;
import io.quarkiverse.work.runtime.model.WorkItem;
import io.quarkiverse.work.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.work.runtime.model.WorkItemPriority;
import io.quarkiverse.work.runtime.model.WorkItemStatus;
import io.quarkiverse.work.runtime.repository.AuditEntryStore;
import io.quarkiverse.work.runtime.repository.WorkItemQuery;
import io.quarkiverse.work.runtime.repository.WorkItemStore;

class WorkItemServiceTest {

    // -------------------------------------------------------------------------
    // In-memory repository implementations
    // -------------------------------------------------------------------------

    static class TestWorkItemRepo implements WorkItemStore {

        private final Map<UUID, WorkItem> store = new ConcurrentHashMap<>();

        @Override
        public WorkItem put(WorkItem workItem) {
            if (workItem.id == null) {
                workItem.id = UUID.randomUUID();
            }
            store.put(workItem.id, workItem);
            return workItem;
        }

        @Override
        public Optional<WorkItem> get(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<WorkItem> scan(WorkItemQuery query) {
            return store.values().stream()
                    .filter(wi -> matchesAssignment(wi, query))
                    .filter(wi -> matchesFilters(wi, query))
                    .toList();
        }

        private boolean matchesAssignment(WorkItem wi, WorkItemQuery q) {
            if (q.assigneeId() == null && (q.candidateGroups() == null || q.candidateGroups().isEmpty())
                    && q.candidateUserId() == null) {
                return true;
            }
            if (q.assigneeId() != null && q.assigneeId().equals(wi.assigneeId)) {
                return true;
            }
            if (q.assigneeId() != null && wi.candidateUsers != null && wi.candidateUsers.contains(q.assigneeId())) {
                return true;
            }
            if (q.candidateGroups() != null && !q.candidateGroups().isEmpty() && wi.candidateGroups != null) {
                for (String g : q.candidateGroups()) {
                    if (wi.candidateGroups.contains(g)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean matchesFilters(WorkItem wi, WorkItemQuery q) {
            if (q.status() != null && wi.status != q.status()) {
                return false;
            }
            if (q.statusIn() != null && !q.statusIn().contains(wi.status)) {
                return false;
            }
            if (q.priority() != null && wi.priority != q.priority()) {
                return false;
            }
            if (q.category() != null && !q.category().equals(wi.category)) {
                return false;
            }
            if (q.followUpBefore() != null
                    && (wi.followUpDate == null || wi.followUpDate.isAfter(q.followUpBefore()))) {
                return false;
            }
            if (q.expiresAtOrBefore() != null
                    && (wi.expiresAt == null || wi.expiresAt.isAfter(q.expiresAtOrBefore()))) {
                return false;
            }
            if (q.claimDeadlineOrBefore() != null
                    && (wi.claimDeadline == null || wi.claimDeadline.isAfter(q.claimDeadlineOrBefore()))) {
                return false;
            }
            if (q.labelPattern() != null) {
                boolean matchesLabel = wi.labels != null && wi.labels.stream()
                        .anyMatch(l -> io.quarkiverse.work.runtime.service.LabelVocabularyService
                                .matchesPattern(q.labelPattern(), l.path));
                if (!matchesLabel) {
                    return false;
                }
            }
            return true;
        }
    }

    static class TestAuditRepo implements AuditEntryStore {

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

        @Override
        public List<AuditEntry> query(io.quarkiverse.work.runtime.repository.AuditQuery query) {
            return List.of();
        }

        @Override
        public long count(io.quarkiverse.work.runtime.repository.AuditQuery query) {
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Config helper
    // -------------------------------------------------------------------------

    static WorkItemsConfig testConfig() {
        return new WorkItemsConfig() {
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

            @Override
            public RoutingConfig routing() {
                return () -> "least-loaded";
            }

            @Override
            public BusinessHoursConfig businessHours() {
                return new BusinessHoursConfig() {
                    @Override
                    public String timezone() {
                        return "UTC";
                    }

                    @Override
                    public String start() {
                        return "09:00";
                    }

                    @Override
                    public String end() {
                        return "17:00";
                    }

                    @Override
                    public String workDays() {
                        return "MON,TUE,WED,THU,FRI";
                    }

                    @Override
                    public java.util.Optional<String> holidays() {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public java.util.Optional<String> holidayIcalUrl() {
                        return java.util.Optional.empty();
                    }
                };
            }
        };
    }

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    private TestWorkItemRepo repo;
    private TestAuditRepo auditStore;
    private WorkItemService service;

    @BeforeEach
    void setUp() {
        repo = new TestWorkItemRepo();
        auditStore = new TestAuditRepo();
        service = new WorkItemService(repo, auditStore, testConfig(),
                new WorkItemAssignmentService(
                        (ctx, candidates) -> AssignmentDecision.noChange(),
                        group -> List.of(),
                        workerId -> 0,
                        new WorkBroker()),
                new io.quarkiverse.work.core.policy.ContinuationPolicy());
    }

    private WorkItemCreateRequest basicRequest() {
        return new WorkItemCreateRequest(
                "Test item", "Do something", null, null,
                WorkItemPriority.NORMAL,
                null, null, null, null,
                "system", null, null, null, null, null, null, null);
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
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id);
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
                "system", null, null, explicit, null, null, null, null);
        WorkItem wi = service.create(req);
        assertThat(wi.expiresAt).isEqualTo(explicit);
    }

    @Test
    void create_withCandidateGroups_storesGroups() {
        WorkItemCreateRequest req = new WorkItemCreateRequest(
                "Group item", null, null, null,
                WorkItemPriority.NORMAL,
                null, "team-a,team-b", null, null,
                "system", null, null, null, null, null, null, null);
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
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id);
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
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id);
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
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id);
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
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id);
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
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id);
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
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id);
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
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id);
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
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id);
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
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id);
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

        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id);
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

        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id);
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

        List<WorkItem> inbox = repo.scan(
                WorkItemQuery.inbox("alice", null, null).toBuilder().status(WorkItemStatus.ASSIGNED).build());
        assertThat(inbox).extracting(w -> w.id).contains(wi.id);
    }

    @Test
    void inbox_findsByCandidateGroup() {
        WorkItemCreateRequest req = new WorkItemCreateRequest(
                "Group task", null, null, null,
                WorkItemPriority.NORMAL,
                null, "team-a,team-b", null, null,
                "system", null, null, null, null, null, null, null);
        WorkItem wi = service.create(req);

        List<WorkItem> inbox = repo.scan(WorkItemQuery.inbox(null, List.of("team-a"), null));
        assertThat(inbox).extracting(w -> w.id).contains(wi.id);
    }

    @Test
    void inbox_findsByCandidateUsers() {
        WorkItemCreateRequest req = new WorkItemCreateRequest(
                "Candidate task", null, null, null,
                WorkItemPriority.NORMAL,
                null, null, "bob", null,
                "system", null, null, null, null, null, null, null);
        WorkItem wi = service.create(req);

        List<WorkItem> inbox = repo.scan(WorkItemQuery.inbox("bob", null, null));
        assertThat(inbox).extracting(w -> w.id).contains(wi.id);
    }

    @Test
    void inbox_completedItemNotReturnedWhenFilteringByPending() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        service.complete(wi.id, "alice", "done");

        List<WorkItem> inbox = repo.scan(
                WorkItemQuery.inbox("alice", null, null).toBuilder().status(WorkItemStatus.PENDING).build());
        assertThat(inbox).extracting(w -> w.id).doesNotContain(wi.id);
    }

    // -------------------------------------------------------------------------
    // Gap-filling: transitions not previously tested
    // -------------------------------------------------------------------------

    // delegate from IN_PROGRESS is valid
    @Test
    void delegate_fromInProgress_isValid() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        wi = service.delegate(wi.id, "alice", "bob");
        assertThat(wi.status).isEqualTo(WorkItemStatus.PENDING);
        assertThat(wi.assigneeId).isEqualTo("bob");
    }

    // release from IN_PROGRESS is invalid
    @Test
    void release_fromInProgress_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        assertThatThrownBy(() -> service.release(wi.id, "alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    // reject from PENDING is invalid
    @Test
    void reject_fromPending_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        assertThatThrownBy(() -> service.reject(wi.id, "alice", "reason"))
                .isInstanceOf(IllegalStateException.class);
    }

    // start from IN_PROGRESS is invalid
    @Test
    void start_fromInProgress_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        assertThatThrownBy(() -> service.start(wi.id, "alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    // suspend from PENDING is invalid
    @Test
    void suspend_fromPending_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        assertThatThrownBy(() -> service.suspend(wi.id, "alice", "reason"))
                .isInstanceOf(IllegalStateException.class);
    }

    // resume from IN_PROGRESS (non-SUSPENDED) is invalid
    @Test
    void resume_fromInProgress_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        assertThatThrownBy(() -> service.resume(wi.id, "alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    // cancel on terminal state is invalid
    @Test
    void cancel_completedItem_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        service.complete(wi.id, "alice", "done");
        assertThatThrownBy(() -> service.cancel(wi.id, "admin", "reason"))
                .isInstanceOf(IllegalStateException.class);
    }

    // default claimDeadline applied
    @Test
    void create_setsClaimDeadlineFromConfigDefault() {
        WorkItem wi = service.create(basicRequest());
        assertThat(wi.claimDeadline).isNotNull();
        Instant expectedApprox = Instant.now().plus(4, ChronoUnit.HOURS);
        assertThat(wi.claimDeadline).isAfter(expectedApprox.minus(5, ChronoUnit.MINUTES));
        assertThat(wi.claimDeadline).isBefore(expectedApprox.plus(5, ChronoUnit.MINUTES));
    }

    // -------------------------------------------------------------------------
    // Label handling at creation
    // -------------------------------------------------------------------------

    @Test
    void create_withInferredLabel_throwsIllegalArgumentException() {
        var request = new WorkItemCreateRequest(
                "title", null, null, null, null, null, null, null, null, "alice",
                null, null, null, null,
                List.of(new WorkItemLabelResponse("legal", LabelPersistence.INFERRED, null)), null, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INFERRED");
    }

    @Test
    void create_withManualLabel_succeeds() {
        var request = new WorkItemCreateRequest(
                "title", null, null, null, null, null, null, null, null, "alice",
                null, null, null, null,
                List.of(new WorkItemLabelResponse("legal", LabelPersistence.MANUAL, "alice")), null, null);

        var result = service.create(request);

        assertThat(result.labels).hasSize(1);
        assertThat(result.labels.get(0).path).isEqualTo("legal");
        assertThat(result.labels.get(0).persistence).isEqualTo(LabelPersistence.MANUAL);
    }

    // -------------------------------------------------------------------------
    // addLabel / removeLabel
    // -------------------------------------------------------------------------

    @Test
    void addLabel_addsManualLabelToWorkItem() {
        var created = service.create(new WorkItemCreateRequest(
                "label-add-test", null, null, null, null, null, null, null, null, "alice",
                null, null, null, null, null, null, null));

        var updated = service.addLabel(created.id, "legal/contracts", "alice");

        assertThat(updated.labels).hasSize(1);
        assertThat(updated.labels.get(0).path).isEqualTo("legal/contracts");
        assertThat(updated.labels.get(0).persistence).isEqualTo(LabelPersistence.MANUAL);
        assertThat(updated.labels.get(0).appliedBy).isEqualTo("alice");
    }

    @Test
    void removeLabel_removesManualLabel() {
        var created = service.create(new WorkItemCreateRequest(
                "label-remove-test", null, null, null, null, null, null, null, null, "alice",
                null, null, null, null,
                List.of(new WorkItemLabelResponse("legal/contracts", LabelPersistence.MANUAL, "alice")), null, null));

        var updated = service.removeLabel(created.id, "legal/contracts");

        assertThat(updated.labels).isEmpty();
    }

    @Test
    void removeLabel_nonExistentLabel_throwsLabelNotFoundException() {
        var created = service.create(new WorkItemCreateRequest(
                "remove-nonexistent", null, null, null, null, null, null, null, null, "alice",
                null, null, null, null, null, null, null));

        assertThatThrownBy(() -> service.removeLabel(created.id, "nonexistent/label"))
                .isInstanceOf(LabelNotFoundException.class)
                .hasMessageContaining("nonexistent/label");
    }

    // when defaultClaimHours=0, no claimDeadline set
    @Test
    void create_withZeroDefaultClaimHours_noClaimDeadlineSet() {
        WorkItemsConfig noClaimConfig = new WorkItemsConfig() {
            @Override
            public int defaultExpiryHours() {
                return 24;
            }

            @Override
            public int defaultClaimHours() {
                return 0;
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

            @Override
            public RoutingConfig routing() {
                return () -> "least-loaded";
            }

            @Override
            public BusinessHoursConfig businessHours() {
                return new BusinessHoursConfig() {
                    @Override
                    public String timezone() {
                        return "UTC";
                    }

                    @Override
                    public String start() {
                        return "09:00";
                    }

                    @Override
                    public String end() {
                        return "17:00";
                    }

                    @Override
                    public String workDays() {
                        return "MON,TUE,WED,THU,FRI";
                    }

                    @Override
                    public java.util.Optional<String> holidays() {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public java.util.Optional<String> holidayIcalUrl() {
                        return java.util.Optional.empty();
                    }
                };
            }
        };
        WorkItemService svc = new WorkItemService(repo, auditStore, noClaimConfig,
                new WorkItemAssignmentService(
                        (ctx, candidates) -> AssignmentDecision.noChange(),
                        group -> List.of(),
                        workerId -> 0,
                        new WorkBroker()),
                new io.quarkiverse.work.core.policy.ContinuationPolicy());
        WorkItem wi = svc.create(basicRequest());
        assertThat(wi.claimDeadline).isNull();
    }
}
