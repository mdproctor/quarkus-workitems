package io.casehub.work.runtime.service;

import io.casehub.platform.api.preferences.MapPreferences;
import io.casehub.platform.expression.DefaultExpressionEngineRegistry;
import io.casehub.platform.expression.JexlExpressionEngine;
import io.casehub.work.api.AssignmentDecision;
import io.casehub.work.api.CompensationStatus;
import io.casehub.work.api.DeclineTarget;
import io.casehub.work.api.LabelPersistence;
import io.casehub.work.api.PolicyDecision;
import io.casehub.work.api.ValidationMode;
import io.casehub.work.api.WorkItem;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.WorkItemLabelRequest;
import io.casehub.work.api.WorkItemPriority;
import io.casehub.work.api.WorkItemQuery;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.api.spi.WorkItemStore;
import io.casehub.work.core.strategy.CapabilityValidator;
import io.casehub.work.runtime.config.WorkItemsConfig;
import io.casehub.work.runtime.event.WorkItemLifecycleEmitter;
import io.casehub.work.runtime.model.AuditEntry;
import io.casehub.work.runtime.repository.AuditEntryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class WorkItemServiceTest {

    // -------------------------------------------------------------------------
    // In-memory repository implementations
    // -------------------------------------------------------------------------

    static class TestWorkItemRepo implements WorkItemStore {

        private final Map<UUID, WorkItem> store = new ConcurrentHashMap<>();

        @Override
        public WorkItem put(WorkItem workItem) {
            WorkItem toStore = workItem;
            if (toStore.id() == null) {
                toStore = toStore.toBuilder().id(UUID.randomUUID()).build();
            }
            store.put(toStore.id(), toStore);
            return toStore;
        }

        @Override
        public Optional<WorkItem> get(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<WorkItem> scan(WorkItemQuery query) {
            return store.values().stream()
                    .filter(wi -> query.tenancyId() == null || query.tenancyId().equals(wi.tenancyId()))
                    .filter(wi -> matchesAssignment(wi, query))
                    .filter(wi -> matchesFilters(wi, query))
                    .toList();
        }

        private boolean matchesAssignment(WorkItem wi, WorkItemQuery q) {
            if (q.assigneeId() == null && (q.candidateGroups() == null || q.candidateGroups().isEmpty())
                    && q.candidateUserId() == null) {
                return true;
            }
            if (q.assigneeId() != null && q.assigneeId().equals(wi.assigneeId())) {
                return true;
            }
            if (q.assigneeId() != null && wi.candidateUsers() != null && wi.candidateUsers().contains(q.assigneeId())) {
                return true;
            }
            if (q.candidateGroups() != null && !q.candidateGroups().isEmpty() && wi.candidateGroups() != null) {
                for (String g : q.candidateGroups()) {
                    if (wi.candidateGroups().contains(g)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean matchesFilters(WorkItem wi, WorkItemQuery q) {
            if (q.status() != null && wi.status() != q.status()) {
                return false;
            }
            if (q.statusIn() != null && !q.statusIn().contains(wi.status())) {
                return false;
            }
            if (q.priority() != null && wi.priority() != q.priority()) {
                return false;
            }
            if (q.type() != null) {
                boolean matched = wi.types() != null && wi.types().stream()
                        .anyMatch(t -> t.equals(q.type()) || t.startsWith(q.type() + "/"));
                if (!matched) return false;
            }
            if (q.followUpBefore() != null
                    && (wi.followUpDate() == null || wi.followUpDate().isAfter(q.followUpBefore()))) {
                return false;
            }
            if (q.expiresAtOrBefore() != null
                    && (wi.expiresAt() == null || wi.expiresAt().isAfter(q.expiresAtOrBefore()))) {
                return false;
            }
            if (q.claimDeadlineOrBefore() != null
                    && (wi.claimDeadline() == null || wi.claimDeadline().isAfter(q.claimDeadlineOrBefore()))) {
                return false;
            }
            if (q.labelPattern() != null) {
                boolean matchesLabel = wi.labels() != null && wi.labels().stream()
                        .anyMatch(l -> io.casehub.work.runtime.service.LabelVocabularyService
                                .matchesPattern(q.labelPattern(), l.path()));
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
        public List<AuditEntry> query(io.casehub.work.runtime.repository.AuditQuery query) {
            return List.of();
        }

        @Override
        public long count(io.casehub.work.runtime.repository.AuditQuery query) {
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
            public CleanupConfig cleanup() {
                return () -> 60;
            }

            @Override
            public io.casehub.work.api.ValidationMode capabilityValidation() {
                return io.casehub.work.api.ValidationMode.PERMISSIVE;
            }

            @Override
            public SlaConfig sla() {
                return new SlaConfig() {
                    @Override public String claimPolicy() { return "continuation"; }
                    @Override public String breachPolicy() { return "no-op"; }
                    @Override public DeclarativeConfig declarative() { return new DeclarativeConfig() {
                        @Override public String fallback() { return "no-op"; }
                        @Override public DefaultsConfig defaults() { return new DefaultsConfig() {
                            @Override public java.util.Optional<String> onCompletionExpiry() { return java.util.Optional.empty(); }
                            @Override public java.util.Optional<String> onClaimExpiry() { return java.util.Optional.empty(); }
                            @Override public java.util.OptionalInt extensionHours() { return java.util.OptionalInt.empty(); }
                            @Override public java.util.OptionalInt claimExtensionHours() { return java.util.OptionalInt.empty(); }
                        }; }
                    }; }
                };
            }

            @Override
            public RoutingConfig routing() {
                return new RoutingConfig() {
                    @Override public String strategy() { return "least-loaded"; }
                    @Override public CursorConfig cursor() {
                        return new CursorConfig() {
                            @Override public int ttlDays() { return 30; }
                            @Override public String cleanupCron() { return "disabled"; }
                        };
                    }
                };
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
        final io.casehub.platform.api.routing.StrategyResolver assignmentResolver =
                org.mockito.Mockito.mock(io.casehub.platform.api.routing.StrategyResolver.class);
        final io.casehub.work.api.spi.WorkerSelectionStrategy noOpStrategy =
                new io.casehub.work.api.spi.WorkerSelectionStrategy() {
                    @Override public String id() { return "test-noop"; }
                    @Override public AssignmentDecision select(
                            final io.casehub.work.api.SelectionContext c,
                            final java.util.List<io.casehub.work.api.WorkerCandidate> w) {
                        return AssignmentDecision.noChange();
                    }
                };
        org.mockito.Mockito.when(assignmentResolver.resolve(
                org.mockito.ArgumentMatchers.eq(io.casehub.work.api.spi.WorkerSelectionStrategy.class),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(noOpStrategy);
        final io.casehub.platform.api.routing.StrategyResolver claimResolver =
                org.mockito.Mockito.mock(io.casehub.platform.api.routing.StrategyResolver.class);
        org.mockito.Mockito.when(claimResolver.resolve(
                org.mockito.ArgumentMatchers.eq(io.casehub.work.api.spi.ClaimSlaPolicy.class),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new io.casehub.work.core.policy.ContinuationPolicy());
        service = new WorkItemService(repo, auditStore, testConfig(),
                new WorkItemAssignmentService(assignmentResolver, testConfig(),
                        group -> List.of(),
                        workerId -> 0,
                        (userId, excluded) -> PolicyDecision.ALLOW),
                claimResolver,
                (userId, excluded) -> PolicyDecision.ALLOW,
                new BlockedAttemptAuditService(auditStore),
                new CapabilityValidator(ValidationMode.PERMISSIVE, () -> java.util.Set.of()),
                mock(WorkItemTimerService.class));
        // Empty preferences → DeclineTarget.POOL by default
        service.preferenceProvider = scope -> new MapPreferences(Map.of());
        // Wire OutcomeValidator — @Inject field, not in constructor
        final OutcomeValidator outcomeValidator = new OutcomeValidator();
        final var registry = new DefaultExpressionEngineRegistry();
        registry.register(new JexlExpressionEngine());
        outcomeValidator.expressionRegistry = registry;
        service.outcomeValidator = outcomeValidator;
        // Wire WorkItemLifecycleEmitter — @Inject field, not in constructor
        service.lifecycleEmitter = mock(WorkItemLifecycleEmitter.class);
    }

    private WorkItemCreateRequest basicRequest() {
        return WorkItemCreateRequest.builder()
                .title("Test item")
                .description("Do something")
                .priority(WorkItemPriority.MEDIUM)
                .createdBy("system")
                .tenancyId("test-tenant")
                .build();
    }

    // -------------------------------------------------------------------------
    // Happy paths — create
    // -------------------------------------------------------------------------

    @Test
    void create_setsStatusPending() {
        WorkItem wi = service.create(basicRequest());
        assertThat(wi.status()).isEqualTo(WorkItemStatus.PENDING);
    }

    @Test
    void create_setsCreatedAt() {
        WorkItem wi = service.create(basicRequest());
        assertThat(wi.createdAt()).isNotNull();
    }

    @Test
    void create_setsExpiresAtFromConfigDefault() {
        WorkItem wi = service.create(basicRequest());
        assertThat(wi.expiresAt()).isNotNull();
        // expiresAt should be approximately now + 24 h
        Instant expectedApprox = Instant.now().plus(24, ChronoUnit.HOURS);
        assertThat(wi.expiresAt()).isAfter(expectedApprox.minus(5, ChronoUnit.MINUTES));
        assertThat(wi.expiresAt()).isBefore(expectedApprox.plus(5, ChronoUnit.MINUTES));
    }

    @Test
    void create_storesTitleAndDescription() {
        WorkItem wi = service.create(basicRequest());
        assertThat(wi.title()).isEqualTo("Test item");
        assertThat(wi.description()).isEqualTo("Do something");
    }

    @Test
    void create_writesCreatedAuditEntry() {
        WorkItem   wi    = service.create(basicRequest());
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id());
        assertThat(trail).hasSize(1);
        assertThat(trail.get(0).event).isEqualTo("CREATED");
    }

    @Test
    void create_withExplicitExpiresAt_usesProvidedValue() {
        Instant explicit = Instant.now().plus(48, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        WorkItemCreateRequest req = WorkItemCreateRequest.builder()
                .title("Explicit expiry")
                .priority(WorkItemPriority.MEDIUM)
                .createdBy("system")
                .expiresAt(explicit)
                .build();
        WorkItem wi = service.create(req);
        assertThat(wi.expiresAt()).isEqualTo(explicit);
    }

    @Test
    void create_withCandidateGroups_storesGroups() {
        WorkItemCreateRequest req = WorkItemCreateRequest.builder()
                .title("Group item")
                .priority(WorkItemPriority.MEDIUM)
                .candidateGroups("team-a,team-b")
                .createdBy("system")
                .build();
        WorkItem wi = service.create(req);
        assertThat(wi.candidateGroups()).isEqualTo("team-a,team-b");
    }

    @Test
    void create_withScope_copiesScopeToWorkItem() {
        WorkItemCreateRequest req = WorkItemCreateRequest.builder()
                .title("Scoped item")
                .createdBy("system")
                .scope("casehubio/devtown/pr-review")
                .build();
        WorkItem wi = service.create(req);
        assertThat(wi.scope()).isEqualTo("casehubio/devtown/pr-review");
    }

    @Test
    void create_withNullScope_workItemScopeIsNull() {
        WorkItem wi = service.create(basicRequest());
        assertThat(wi.scope()).isNull();
    }

    // -------------------------------------------------------------------------
    // Happy paths — claim
    // -------------------------------------------------------------------------

    @Test
    void claim_transitionsPendingToAssigned() {
        WorkItem wi = service.create(basicRequest());
        wi = service.claim(wi.id(), "alice");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.ASSIGNED);
    }

    @Test
    void claim_setsAssignedAtAndAssigneeId() {
        WorkItem wi = service.create(basicRequest());
        wi = service.claim(wi.id(), "alice");
        assertThat(wi.assignedAt()).isNotNull();
        assertThat(wi.assigneeId()).isEqualTo("alice");
    }

    @Test
    void claim_writesAssignedAuditEntry() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id());
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
        wi = service.claim(wi.id(), "alice");
        wi = service.start(wi.id(), "alice");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.IN_PROGRESS);
    }

    @Test
    void start_setsStartedAt() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        wi = service.start(wi.id(), "alice");
        assertThat(wi.startedAt()).isNotNull();
    }

    @Test
    void start_writesStartedAuditEntry() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id());
        assertThat(trail.get(trail.size() - 1).event).isEqualTo("STARTED");
    }

    // -------------------------------------------------------------------------
    // Happy paths — complete
    // -------------------------------------------------------------------------

    @Test
    void complete_transitionsInProgressToCompleted() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        wi = service.complete(wi.id(), "alice", "Done", null);
        assertThat(wi.status()).isEqualTo(WorkItemStatus.COMPLETED);
    }

    @Test
    void complete_setsCompletedAtAndResolution() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        wi = service.complete(wi.id(), "alice", "Resolved successfully", null);
        assertThat(wi.completedAt()).isNotNull();
        assertThat(wi.resolution()).isEqualTo("Resolved successfully");
    }

    @Test
    void complete_writesCompletedAuditEntry() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        service.complete(wi.id(), "alice", "Done", null);
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id());
        assertThat(trail.get(trail.size() - 1).event).isEqualTo("COMPLETED");
    }

    // -------------------------------------------------------------------------
    // Happy paths — reject
    // -------------------------------------------------------------------------

    @Test
    void reject_fromInProgress_transitionsToRejected() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        wi = service.reject(wi.id(), "alice", "Not my responsibility", null);
        assertThat(wi.status()).isEqualTo(WorkItemStatus.REJECTED);
    }

    @Test
    void reject_fromInProgress_setsCompletedAt() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        wi = service.reject(wi.id(), "alice", "Out of scope", null);
        assertThat(wi.completedAt()).isNotNull();
    }

    @Test
    void reject_fromAssigned_isValid() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        wi = service.reject(wi.id(), "alice", "Wrong team", null);
        assertThat(wi.status()).isEqualTo(WorkItemStatus.REJECTED);
    }

    @Test
    void reject_writesRejectedAuditEntry() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.reject(wi.id(), "alice", "Not applicable", null);
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id());
        assertThat(trail.get(trail.size() - 1).event).isEqualTo("REJECTED");
    }

    // -------------------------------------------------------------------------
    // Happy paths — delegate
    // -------------------------------------------------------------------------

    @Test
    void delegate_firstTime_setsOwnerToActor() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        wi = service.delegate(wi.id(), "alice", "bob", null);
        assertThat(wi.owner()).isEqualTo("alice");
    }

    @Test
    void delegate_setsNewAssigneeAndStatusDelegated() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        wi = service.delegate(wi.id(), "alice", "bob", null);
        assertThat(wi.assigneeId()).isEqualTo("bob");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.DELEGATED);
    }

    @Test
    void delegate_clearsClaimDeadline() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        wi = service.delegate(wi.id(), "alice", "bob", null);
        assertThat(wi.claimDeadline()).isNull();
    }

    @Test
    void delegate_clearsLastReturnedToPoolAt() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        wi = service.delegate(wi.id(), "alice", "bob", null);
        assertThat(wi.lastReturnedToPoolAt()).isNull();
    }

    @Test
    void delegate_withDeclineTargetDelegator_storesDeclineTarget() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        wi = service.delegate(wi.id(), "alice", "bob", DeclineTarget.DELEGATOR);
        assertThat(wi.delegationDeclineTarget()).isEqualTo(DeclineTarget.DELEGATOR);
    }

    @Test
    void delegate_withNullDeclineTarget_leavesDeclineTargetNull() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        wi = service.delegate(wi.id(), "alice", "bob", null);
        assertThat(wi.delegationDeclineTarget()).isNull();
    }

    // delegate_setsDelegationStatePending removed — DelegationState dropped in #245;
    // WorkItemStatus.DELEGATED now carries the pre-acceptance semantic

    @Test
    void delegate_addsDelegatorToDelegationChain() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        wi = service.delegate(wi.id(), "alice", "bob", null);
        assertThat(wi.delegationChain()).contains("alice");
    }

    @Test
    void delegate_writesDelegatedAuditEntry() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.delegate(wi.id(), "alice", "bob", null);
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id());
        assertThat(trail.get(trail.size() - 1).event).isEqualTo("DELEGATED");
    }

    @Test
    void delegate_secondTime_doesNotOverwriteOwner() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        wi = service.delegate(wi.id(), "alice", "bob", null);
        // bob accepts then re-delegates
        service.acceptDelegation(wi.id(), "bob");
        wi = service.delegate(wi.id(), "bob", "carol", null);
        assertThat(wi.owner()).isEqualTo("alice");
    }

    @Test
    void delegate_secondTime_delegationChainGrows() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        wi = service.delegate(wi.id(), "alice", "bob", null);
        // bob must accept the delegation before they can re-delegate
        service.acceptDelegation(wi.id(), "bob");
        wi = service.delegate(wi.id(), "bob", "carol", null);
        assertThat(wi.delegationChain()).contains("alice");
        assertThat(wi.delegationChain()).contains("bob");
    }

    // -------------------------------------------------------------------------
    // Happy paths — acceptDelegation
    // -------------------------------------------------------------------------

    @Test
    void acceptDelegation_transitionsToAssigned() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.delegate(wi.id(), "alice", "bob", null);
        wi = service.acceptDelegation(wi.id(), "bob");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.ASSIGNED);
        assertThat(wi.assigneeId()).isEqualTo("bob");
    }

    @Test
    void acceptDelegation_setsAssignedAt() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.delegate(wi.id(), "alice", "bob", null);
        wi = service.acceptDelegation(wi.id(), "bob");
        assertThat(wi.assignedAt()).isNotNull();
    }

    @Test
    void acceptDelegation_clearsDeclineTarget() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.delegate(wi.id(), "alice", "bob", DeclineTarget.DELEGATOR);
        wi = service.acceptDelegation(wi.id(), "bob");
        assertThat(wi.delegationDeclineTarget()).isNull();
    }

    @Test
    void acceptDelegation_wrongActor_throws() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.delegate(wi.id(), "alice", "bob", null);
        final UUID id = wi.id();
        assertThatThrownBy(() -> service.acceptDelegation(id, "charlie"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptDelegation_notDelegated_throws() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        final UUID id = wi.id();
        assertThatThrownBy(() -> service.acceptDelegation(id, "alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // Happy paths — declineDelegation POOL
    // -------------------------------------------------------------------------

    @Test
    void declineDelegation_poolPath_transitionsToPending() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.delegate(wi.id(), "alice", "bob", DeclineTarget.POOL);
        wi = service.declineDelegation(wi.id(), "bob");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.PENDING);
        assertThat(wi.assigneeId()).isNull();
    }

    @Test
    void declineDelegation_poolPath_setsLastReturnedToPoolAt() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.delegate(wi.id(), "alice", "bob", DeclineTarget.POOL);
        wi = service.declineDelegation(wi.id(), "bob");
        assertThat(wi.lastReturnedToPoolAt()).isNotNull();
    }

    @Test
    void declineDelegation_poolPath_recomputesClaimDeadline() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.delegate(wi.id(), "alice", "bob", DeclineTarget.POOL);
        wi = service.declineDelegation(wi.id(), "bob");
        assertThat(wi.claimDeadline()).isNotNull();
    }

    @Test
    void declineDelegation_poolPath_clearsDeclineTarget() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.delegate(wi.id(), "alice", "bob", DeclineTarget.POOL);
        wi = service.declineDelegation(wi.id(), "bob");
        assertThat(wi.delegationDeclineTarget()).isNull();
    }

    // -------------------------------------------------------------------------
    // Happy paths — declineDelegation DELEGATOR
    // -------------------------------------------------------------------------

    @Test
    void declineDelegation_delegatorPath_returnsToOriginalActor() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.delegate(wi.id(), "alice", "bob", DeclineTarget.DELEGATOR);
        wi = service.declineDelegation(wi.id(), "bob");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.ASSIGNED);
        assertThat(wi.assigneeId()).isEqualTo("alice");
    }

    @Test
    void declineDelegation_delegatorPathWithNullChain_fallsBackToPool() {
        // If DELEGATOR is requested but delegationChain is null, falls back to POOL.
        // Prevents NPE if a WorkItem reaches DELEGATED status through an unusual path.
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        wi = service.delegate(wi.id(), "alice", "bob", DeclineTarget.DELEGATOR);
        // Clear chain to simulate unusual state
        wi = wi.toBuilder().delegationChain(null).build();
        repo.put(wi);
        wi = service.declineDelegation(wi.id(), "bob");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.PENDING); // POOL fallback
        assertThat(wi.assigneeId()).isNull();
    }

    @Test
    void declineDelegation_usesPreferenceDefaultWhenNoInstanceOverride() {
        // setUp wires empty preferences → POOL default
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.delegate(wi.id(), "alice", "bob", null); // no instance override
        wi = service.declineDelegation(wi.id(), "bob");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.PENDING); // POOL behaviour
    }

    // -------------------------------------------------------------------------
    // Error cases — declineDelegation
    // -------------------------------------------------------------------------

    @Test
    void declineDelegation_wrongActor_throws() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.delegate(wi.id(), "alice", "bob", null);
        final UUID id = wi.id();
        assertThatThrownBy(() -> service.declineDelegation(id, "charlie"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void declineDelegation_notDelegated_throws() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        final UUID id = wi.id();
        assertThatThrownBy(() -> service.declineDelegation(id, "alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // Happy paths — release
    // -------------------------------------------------------------------------

    @Test
    void release_transitionsAssignedToPending() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        wi = service.release(wi.id(), "alice");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.PENDING);
    }

    @Test
    void release_clearsAssigneeId() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        wi = service.release(wi.id(), "alice");
        assertThat(wi.assigneeId()).isNull();
    }

    @Test
    void release_writesReleasedAuditEntry() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.release(wi.id(), "alice");
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id());
        assertThat(trail.get(trail.size() - 1).event).isEqualTo("RELEASED");
    }

    // -------------------------------------------------------------------------
    // Happy paths — suspend
    // -------------------------------------------------------------------------

    @Test
    void suspend_fromAssigned_transitionsToSuspended() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        wi = service.suspend(wi.id(), "alice", "waiting for input");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.SUSPENDED);
    }

    @Test
    void suspend_fromAssigned_setsSuspendedAtAndPriorStatus() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        wi = service.suspend(wi.id(), "alice", "blocked");
        assertThat(wi.suspendedAt()).isNotNull();
        assertThat(wi.priorStatus()).isEqualTo(WorkItemStatus.ASSIGNED);
    }

    @Test
    void suspend_fromInProgress_setsPriorStatusInProgress() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        wi = service.suspend(wi.id(), "alice", "waiting");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.SUSPENDED);
        assertThat(wi.priorStatus()).isEqualTo(WorkItemStatus.IN_PROGRESS);
    }

    @Test
    void suspend_writesSuspendedAuditEntry() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.suspend(wi.id(), "alice", "blocked");
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id());
        assertThat(trail.get(trail.size() - 1).event).isEqualTo("SUSPENDED");
    }

    // -------------------------------------------------------------------------
    // Happy paths — resume
    // -------------------------------------------------------------------------

    @Test
    void resume_afterAssignedSuspend_returnsToAssigned() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.suspend(wi.id(), "alice", "wait");
        wi = service.resume(wi.id(), "alice");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.ASSIGNED);
    }

    @Test
    void resume_clearsSuspendedAt() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.suspend(wi.id(), "alice", "wait");
        wi = service.resume(wi.id(), "alice");
        assertThat(wi.suspendedAt()).isNull();
    }

    @Test
    void resume_writesResumedAuditEntry() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.suspend(wi.id(), "alice", "wait");
        service.resume(wi.id(), "alice");
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id());
        assertThat(trail.get(trail.size() - 1).event).isEqualTo("RESUMED");
    }

    @Test
    void resume_afterInProgressSuspend_returnsToInProgress() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        service.suspend(wi.id(), "alice", "wait");
        wi = service.resume(wi.id(), "alice");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.IN_PROGRESS);
    }

    // -------------------------------------------------------------------------
    // Happy paths — cancel
    // -------------------------------------------------------------------------

    @Test
    void cancel_fromPending_transitionsToCancelled() {
        WorkItem wi = service.create(basicRequest());
        wi = service.cancel(wi.id(), "admin", "no longer needed");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.CANCELLED);
    }

    @Test
    void cancel_fromPending_setsCompletedAt() {
        WorkItem wi = service.create(basicRequest());
        wi = service.cancel(wi.id(), "admin", "obsolete");
        assertThat(wi.completedAt()).isNotNull();
    }

    @Test
    void cancel_writesCancelledAuditEntry() {
        WorkItem wi = service.create(basicRequest());
        service.cancel(wi.id(), "admin", "no longer needed");
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id());
        assertThat(trail.get(trail.size() - 1).event).isEqualTo("CANCELLED");
    }

    @Test
    void cancel_fromAssigned_transitionsToCancelled() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        wi = service.cancel(wi.id(), "admin", "revoked");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.CANCELLED);
    }

    @Test
    void cancel_fromInProgress_transitionsToCancelled() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        wi = service.cancel(wi.id(), "admin", "project cancelled");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.CANCELLED);
    }

    @Test
    void cancel_fromSuspended_transitionsToCancelled() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.suspend(wi.id(), "alice", "blocked");
        wi = service.cancel(wi.id(), "admin", "giving up");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.CANCELLED);
    }

    // -------------------------------------------------------------------------
    // Invalid transitions — expect IllegalStateException
    // -------------------------------------------------------------------------

    @Test
    void complete_pendingItem_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        assertThatThrownBy(() -> service.complete(wi.id(), "alice", "done", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void complete_completedItem_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        service.complete(wi.id(), "alice", "done", null);
        assertThatThrownBy(() -> service.complete(wi.id(), "alice", "done again", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void start_pendingItem_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        assertThatThrownBy(() -> service.start(wi.id(), "alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void claim_assignedItem_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        assertThatThrownBy(() -> service.claim(wi.id(), "bob"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void release_pendingItem_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        assertThatThrownBy(() -> service.release(wi.id(), "alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void suspend_completedItem_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        service.complete(wi.id(), "alice", "done", null);
        assertThatThrownBy(() -> service.suspend(wi.id(), "alice", "wait"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resume_inProgressItem_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        assertThatThrownBy(() -> service.resume(wi.id(), "alice"))
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
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        service.complete(wi.id(), "alice", "done", null);

        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id());
        assertThat(trail).hasSize(4);
        assertThat(trail.get(0).event).isEqualTo("CREATED");
        assertThat(trail.get(1).event).isEqualTo("ASSIGNED");
        assertThat(trail.get(2).event).isEqualTo("STARTED");
        assertThat(trail.get(3).event).isEqualTo("COMPLETED");
    }

    @Test
    void auditTrail_actorIsRecordedCorrectly() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");

        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id());
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
        service.claim(wi.id(), "alice");

        List<WorkItem> inbox = repo.scan(
                WorkItemQuery.inbox("alice", null, null).toBuilder().status(WorkItemStatus.ASSIGNED).build());
        assertThat(inbox).extracting(w -> w.id()).contains(wi.id());
    }

    @Test
    void inbox_findsByCandidateGroup() {
        WorkItemCreateRequest req = WorkItemCreateRequest.builder()
                .title("Group task")
                .priority(WorkItemPriority.MEDIUM)
                .candidateGroups("team-a,team-b")
                .createdBy("system")
                .build();
        WorkItem wi = service.create(req);

        List<WorkItem> inbox = repo.scan(WorkItemQuery.inbox(null, List.of("team-a"), null));
        assertThat(inbox).extracting(w -> w.id()).contains(wi.id());
    }

    @Test
    void inbox_findsByCandidateUsers() {
        WorkItemCreateRequest req = WorkItemCreateRequest.builder()
                .title("Candidate task")
                .priority(WorkItemPriority.MEDIUM)
                .candidateUsers("bob")
                .createdBy("system")
                .build();
        WorkItem wi = service.create(req);

        List<WorkItem> inbox = repo.scan(WorkItemQuery.inbox("bob", null, null));
        assertThat(inbox).extracting(w -> w.id()).contains(wi.id());
    }

    @Test
    void inbox_completedItemNotReturnedWhenFilteringByPending() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        service.complete(wi.id(), "alice", "done", null);

        List<WorkItem> inbox = repo.scan(
                WorkItemQuery.inbox("alice", null, null).toBuilder().status(WorkItemStatus.PENDING).build());
        assertThat(inbox).extracting(w -> w.id()).doesNotContain(wi.id());
    }

    // -------------------------------------------------------------------------
    // Gap-filling: transitions not previously tested
    // -------------------------------------------------------------------------

    // delegate from IN_PROGRESS is valid
    @Test
    void delegate_fromInProgress_isValid() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        wi = service.delegate(wi.id(), "alice", "bob", null);
        assertThat(wi.status()).isEqualTo(WorkItemStatus.DELEGATED);
        assertThat(wi.assigneeId()).isEqualTo("bob");
    }

    // release from IN_PROGRESS is invalid
    @Test
    void release_fromInProgress_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        assertThatThrownBy(() -> service.release(wi.id(), "alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    // reject from PENDING is invalid
    @Test
    void reject_fromPending_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        assertThatThrownBy(() -> service.reject(wi.id(), "alice", "reason", null))
                .isInstanceOf(IllegalStateException.class);
    }

    // start from IN_PROGRESS is invalid
    @Test
    void start_fromInProgress_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        assertThatThrownBy(() -> service.start(wi.id(), "alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    // suspend from PENDING is invalid
    @Test
    void suspend_fromPending_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        assertThatThrownBy(() -> service.suspend(wi.id(), "alice", "reason"))
                .isInstanceOf(IllegalStateException.class);
    }

    // resume from IN_PROGRESS (non-SUSPENDED) is invalid
    @Test
    void resume_fromInProgress_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        assertThatThrownBy(() -> service.resume(wi.id(), "alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    // cancel on terminal state is invalid
    @Test
    void cancel_completedItem_throwsIllegalStateException() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        service.complete(wi.id(), "alice", "done", null);
        assertThatThrownBy(() -> service.cancel(wi.id(), "admin", "reason"))
                .isInstanceOf(IllegalStateException.class);
    }

    // default claimDeadline applied
    @Test
    void create_setsClaimDeadlineFromConfigDefault() {
        WorkItem wi = service.create(basicRequest());
        assertThat(wi.claimDeadline()).isNotNull();
        Instant expectedApprox = Instant.now().plus(4, ChronoUnit.HOURS);
        assertThat(wi.claimDeadline()).isAfter(expectedApprox.minus(5, ChronoUnit.MINUTES));
        assertThat(wi.claimDeadline()).isBefore(expectedApprox.plus(5, ChronoUnit.MINUTES));
    }

    // -------------------------------------------------------------------------
    // Label handling at creation
    // -------------------------------------------------------------------------

    @Test
    void create_withInferredLabel_throwsIllegalArgumentException() {
        var request = WorkItemCreateRequest.builder()
                .title("title")
                .createdBy("alice")
                .labels(List.of(new WorkItemLabelRequest("legal", LabelPersistence.INFERRED, null)))
                .build();

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INFERRED");
    }

    @Test
    void create_withManualLabel_succeeds() {
        var request = WorkItemCreateRequest.builder()
                .title("title")
                .createdBy("alice")
                .labels(List.of(new WorkItemLabelRequest("legal", LabelPersistence.MANUAL, "alice")))
                .build();

        var result = service.create(request);

        assertThat(result.labels()).hasSize(1);
        assertThat(result.labels().get(0).path()).isEqualTo("legal");
        assertThat(result.labels().get(0).persistence()).isEqualTo(LabelPersistence.MANUAL);
    }

    // -------------------------------------------------------------------------
    // addLabel / removeLabel
    // -------------------------------------------------------------------------

    @Test
    void addLabel_addsManualLabelToWorkItem() {
        var created = service.create(WorkItemCreateRequest.builder()
                .title("label-add-test")
                .createdBy("alice")
                .build());

        var updated = service.addLabel(created.id(), "legal/contracts", "alice");

        assertThat(updated.labels()).hasSize(1);
        assertThat(updated.labels().get(0).path()).isEqualTo("legal/contracts");
        assertThat(updated.labels().get(0).persistence()).isEqualTo(LabelPersistence.MANUAL);
        assertThat(updated.labels().get(0).appliedBy()).isEqualTo("alice");
    }

    @Test
    void removeLabel_removesManualLabel() {
        var created = service.create(WorkItemCreateRequest.builder()
                .title("label-remove-test")
                .createdBy("alice")
                .labels(List.of(new WorkItemLabelRequest("legal/contracts", LabelPersistence.MANUAL, "alice")))
                .build());

        var updated = service.removeLabel(created.id(), "legal/contracts");

        assertThat(updated.labels()).isEmpty();
    }

    @Test
    void removeLabel_nonExistentLabel_throwsLabelNotFoundException() {
        var created = service.create(WorkItemCreateRequest.builder()
                .title("remove-nonexistent")
                .createdBy("alice")
                .build());

        assertThatThrownBy(() -> service.removeLabel(created.id(), "nonexistent/label"))
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
            public CleanupConfig cleanup() {
                return () -> 60;
            }

            @Override
            public io.casehub.work.api.ValidationMode capabilityValidation() {
                return io.casehub.work.api.ValidationMode.PERMISSIVE;
            }

            @Override
            public SlaConfig sla() {
                return new SlaConfig() {
                    @Override public String claimPolicy() { return "continuation"; }
                    @Override public String breachPolicy() { return "no-op"; }
                    @Override public DeclarativeConfig declarative() { return new DeclarativeConfig() {
                        @Override public String fallback() { return "no-op"; }
                        @Override public DefaultsConfig defaults() { return new DefaultsConfig() {
                            @Override public java.util.Optional<String> onCompletionExpiry() { return java.util.Optional.empty(); }
                            @Override public java.util.Optional<String> onClaimExpiry() { return java.util.Optional.empty(); }
                            @Override public java.util.OptionalInt extensionHours() { return java.util.OptionalInt.empty(); }
                            @Override public java.util.OptionalInt claimExtensionHours() { return java.util.OptionalInt.empty(); }
                        }; }
                    }; }
                };
            }

            @Override
            public RoutingConfig routing() {
                return new RoutingConfig() {
                    @Override public String strategy() { return "least-loaded"; }
                    @Override public CursorConfig cursor() {
                        return new CursorConfig() {
                            @Override public int ttlDays() { return 30; }
                            @Override public String cleanupCron() { return "disabled"; }
                        };
                    }
                };
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
        final io.casehub.platform.api.routing.StrategyResolver noClaimResolver =
                org.mockito.Mockito.mock(io.casehub.platform.api.routing.StrategyResolver.class);
        final io.casehub.work.api.spi.WorkerSelectionStrategy noClaimNoOp =
                new io.casehub.work.api.spi.WorkerSelectionStrategy() {
                    @Override public String id() { return "test-noop"; }
                    @Override public AssignmentDecision select(
                            final io.casehub.work.api.SelectionContext c,
                            final java.util.List<io.casehub.work.api.WorkerCandidate> w) {
                        return AssignmentDecision.noChange();
                    }
                };
        org.mockito.Mockito.when(noClaimResolver.resolve(
                org.mockito.ArgumentMatchers.eq(io.casehub.work.api.spi.WorkerSelectionStrategy.class),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(noClaimNoOp);
        final io.casehub.platform.api.routing.StrategyResolver noClaimPolicyResolver =
                org.mockito.Mockito.mock(io.casehub.platform.api.routing.StrategyResolver.class);
        org.mockito.Mockito.when(noClaimPolicyResolver.resolve(
                org.mockito.ArgumentMatchers.eq(io.casehub.work.api.spi.ClaimSlaPolicy.class),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new io.casehub.work.core.policy.ContinuationPolicy());
        WorkItemService svc = new WorkItemService(repo, auditStore, noClaimConfig,
                new WorkItemAssignmentService(noClaimResolver, noClaimConfig,
                        group -> List.of(),
                        workerId -> 0,
                        (userId, excluded) -> PolicyDecision.ALLOW),
                noClaimPolicyResolver,
                (userId, excluded) -> PolicyDecision.ALLOW,
                new BlockedAttemptAuditService(auditStore),
                new CapabilityValidator(ValidationMode.PERMISSIVE, () -> java.util.Set.of()),
                mock(WorkItemTimerService.class));
        svc.lifecycleEmitter = mock(WorkItemLifecycleEmitter.class);
        WorkItem wi = svc.create(basicRequest());
        assertThat(wi.claimDeadline()).isNull();
    }

    // -------------------------------------------------------------------------
    // findById (#241)
    // -------------------------------------------------------------------------

    @Test
    void findById_existingItem_returnsItem() {
        final WorkItem wi = service.create(basicRequest());
        assertThat(service.findById(wi.id()))
                .isPresent()
                .get()
                .extracting(w -> w.id())
                .isEqualTo(wi.id());
    }

    @Test
    void findById_unknownId_returnsEmpty() {
        assertThat(service.findById(UUID.randomUUID())).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Happy paths — fault
    // -------------------------------------------------------------------------

    @Test
    void fault_fromPending_transitionsToFaulted() {
        WorkItem wi = service.create(basicRequest());
        wi = service.fault(wi.id(), "system", "infrastructure failure");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.FAULTED);
    }

    @Test
    void fault_fromAssigned_transitionsToFaulted() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        wi = service.fault(wi.id(), "system", "agent timeout");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.FAULTED);
    }

    @Test
    void fault_fromInProgress_transitionsToFaulted() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        wi = service.fault(wi.id(), "system", "context overflow");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.FAULTED);
    }

    @Test
    void fault_fromSuspended_transitionsToFaulted() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.suspend(wi.id(), "alice", "paused");
        wi = service.fault(wi.id(), "system", "host crashed");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.FAULTED);
    }

    @Test
    void fault_fromDelegated_transitionsToFaulted() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.delegate(wi.id(), "alice", "bob", null);
        wi = service.fault(wi.id(), "system", "delegation target unreachable");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.FAULTED);
    }

    @Test
    void fault_setsCompletedAt() {
        WorkItem wi = service.create(basicRequest());
        wi = service.fault(wi.id(), "system", "failure");
        assertThat(wi.completedAt()).isNotNull();
    }

    @Test
    void fault_setsResolution() {
        WorkItem wi = service.create(basicRequest());
        wi = service.fault(wi.id(), "system", "API timeout");
        assertThat(wi.resolution()).isEqualTo("API timeout");
    }

    @Test
    void fault_fromTerminal_throws() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        service.complete(wi.id(), "alice", "done", null);
        final UUID id = wi.id();
        assertThatThrownBy(() -> service.fault(id, "system", "too late"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void faultFromSystem_fromPending_transitionsToFaulted() {
        WorkItem wi = service.create(basicRequest());
        wi = service.faultFromSystem(wi.id(), "system", "infrastructure failure");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.FAULTED);
    }

    @Test
    void faultFromSystem_fromTerminal_returnsSilently() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        service.complete(wi.id(), "alice", "done", null);
        wi = service.faultFromSystem(wi.id(), "system", "too late");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.COMPLETED);
    }

    // -------------------------------------------------------------------------
    // Happy paths — obsolete
    // -------------------------------------------------------------------------

    @Test
    void obsolete_fromPending_transitionsToObsolete() {
        WorkItem wi = service.create(basicRequest());
        wi = service.obsolete(wi.id(), "system", "case withdrawn");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.OBSOLETE);
    }

    @Test
    void obsolete_fromInProgress_transitionsToObsolete() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        wi = service.obsolete(wi.id(), "system", "context changed");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.OBSOLETE);
    }

    @Test
    void obsolete_setsCompletedAt() {
        WorkItem wi = service.create(basicRequest());
        wi = service.obsolete(wi.id(), "system", "superseded");
        assertThat(wi.completedAt()).isNotNull();
    }

    @Test
    void obsolete_setsResolution() {
        WorkItem wi = service.create(basicRequest());
        wi = service.obsolete(wi.id(), "system", "trial withdrawn");
        assertThat(wi.resolution()).isEqualTo("trial withdrawn");
    }

    @Test
    void obsolete_fromTerminal_throws() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        service.complete(wi.id(), "alice", "done", null);
        final UUID id = wi.id();
        assertThatThrownBy(() -> service.obsolete(id, "system", "too late"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void obsoleteFromSystem_fromPending_transitionsToObsolete() {
        WorkItem wi = service.create(basicRequest());
        wi = service.obsoleteFromSystem(wi.id(), "system", "context changed");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.OBSOLETE);
    }

    @Test
    void obsoleteFromSystem_fromTerminal_returnsSilently() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        service.complete(wi.id(), "alice", "done", null);
        wi = service.obsoleteFromSystem(wi.id(), "system", "too late");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.COMPLETED);
    }

    // -------------------------------------------------------------------------
    // Happy paths — cancelFromSystem
    // -------------------------------------------------------------------------

    @Test
    void cancelFromSystem_fromPending_transitionsToCancelled() {
        WorkItem wi = service.create(basicRequest());
        wi = service.cancelFromSystem(wi.id(), "system", "group policy");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.CANCELLED);
    }

    @Test
    void cancelFromSystem_fromTerminal_returnsSilently() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        service.complete(wi.id(), "alice", "done", null);
        wi = service.cancelFromSystem(wi.id(), "system", "too late");
        assertThat(wi.status()).isEqualTo(WorkItemStatus.COMPLETED);
    }

    // -------------------------------------------------------------------------
    // Happy paths — progress
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // scan (#286)
    // -------------------------------------------------------------------------

    @Test
    void scan_byStatus_returnsMatchingItems() {
        service.create(basicRequest());
        WorkItem wi2 = service.create(basicRequest());
        service.claim(wi2.id(), "alice");

        List<WorkItem> pending = service.scan(
                WorkItemQuery.builder().status(WorkItemStatus.PENDING).build());
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).status()).isEqualTo(WorkItemStatus.PENDING);

        List<WorkItem> assigned = service.scan(
                WorkItemQuery.builder().status(WorkItemStatus.ASSIGNED).build());
        assertThat(assigned).hasSize(1);
        assertThat(assigned.get(0).id()).isEqualTo(wi2.id());
    }

    @Test
    void scan_all_returnsAllItems() {
        service.create(basicRequest());
        service.create(basicRequest());
        service.create(basicRequest());

        List<WorkItem> all = service.scan(WorkItemQuery.all());
        assertThat(all).hasSize(3);
    }

    @Test
    void scan_byTenancyId_filtersCorrectly() {
        WorkItem wi1 = service.create(basicRequest());
        wi1 = wi1.toBuilder().tenancyId("tenant-A").build();
        repo.put(wi1);

        WorkItem wi2 = service.create(basicRequest());
        wi2 = wi2.toBuilder().tenancyId("tenant-B").build();
        repo.put(wi2);

        List<WorkItem> tenantA = service.scan(
                WorkItemQuery.builder().tenancyId("tenant-A").build());
        assertThat(tenantA).hasSize(1);
        assertThat(tenantA.get(0).tenancyId()).isEqualTo("tenant-A");

        List<WorkItem> tenantB = service.scan(
                WorkItemQuery.builder().tenancyId("tenant-B").build());
        assertThat(tenantB).hasSize(1);
        assertThat(tenantB.get(0).tenancyId()).isEqualTo("tenant-B");
    }

    @Test
    void scan_byStatusAndType_combinesFilters() {
        WorkItem wi1 = service.create(WorkItemCreateRequest.builder()
                                                                 .title("Legal review").types(List.of("legal")).createdBy("system").build());
        WorkItem wi2 = service.create(WorkItemCreateRequest.builder()
                                                                 .title("Finance review").types(List.of("finance")).createdBy("system").build());
        service.claim(wi2.id(), "alice");

        List<WorkItem> pendingLegal = service.scan(
                WorkItemQuery.builder().status(WorkItemStatus.PENDING).type("legal").build());
        assertThat(pendingLegal).hasSize(1);
        assertThat(pendingLegal.get(0).id()).isEqualTo(wi1.id());
    }

    @Test
    void scan_emptyResult_returnsEmptyList() {
        List<WorkItem> result = service.scan(
                WorkItemQuery.builder().status(WorkItemStatus.COMPLETED).build());
        assertThat(result).isEmpty();
    }

    @Test
    void updateDeadline_setsNewExpiresAt() {
        WorkItem wi          = service.create(basicRequest());
        Instant        newDeadline = Instant.now().plus(48, ChronoUnit.HOURS);
        WorkItem updated     = service.updateDeadline(wi.id(), newDeadline, "admin");
        assertThat(updated.expiresAt()).isEqualTo(newDeadline);
    }

    @Test
    void updateDeadline_allowsEarlierDeadline() {
        WorkItem wi      = service.create(basicRequest());
        Instant        earlier = Instant.now().plus(1, ChronoUnit.HOURS);
        WorkItem updated = service.updateDeadline(wi.id(), earlier, "admin");
        assertThat(updated.expiresAt()).isEqualTo(earlier);
    }

    @Test
    void updateDeadline_writesAuditWithOldAndNew() {
        WorkItem wi          = service.create(basicRequest());
        Instant        oldDeadline = wi.expiresAt();
        Instant  newDeadline = Instant.now().plus(48, ChronoUnit.HOURS);
        service.updateDeadline(wi.id(), newDeadline, "admin");
        List<AuditEntry> trail = auditStore.findByWorkItemId(wi.id());
        AuditEntry deadlineEntry = trail.stream()
                                        .filter(a -> "DEADLINE_UPDATED".equals(a.event))
                                        .findFirst().orElseThrow();
        assertThat(deadlineEntry.actor).isEqualTo("admin");
        assertThat(deadlineEntry.detail).contains("old=" + oldDeadline);
        assertThat(deadlineEntry.detail).contains("new=" + newDeadline);
    }

    @Test
    void updateDeadline_terminalStatus_throws() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "user1");
        service.start(wi.id(), "user1");
        service.complete(wi.id(), "user1", "done", null);
        Instant newDeadline = Instant.now().plus(48, ChronoUnit.HOURS);
        assertThatThrownBy(() -> service.updateDeadline(wi.id(), newDeadline, "admin"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updateDeadline_nullDeadline_throws() {
        WorkItem wi = service.create(basicRequest());
        assertThatThrownBy(() -> service.updateDeadline(wi.id(), null, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }


// -------------------------------------------------------------------------
// Compensation
// -------------------------------------------------------------------------

    private WorkItem createCompletedWorkItem() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id(), "alice");
        service.start(wi.id(), "alice");
        return service.complete(wi.id(), "alice", "Done", null);
    }

    @Test
    void compensate_completedWorkItem_createsCompensatingWorkItem() {
        WorkItem original = createCompletedWorkItem();
        WorkItemCreateRequest compReq = WorkItemCreateRequest.builder()
                                                             .title("Reverse: " + original.title())
                                                             .candidateGroups("reviewers")
                                                             .createdBy("operator-1")
                                                             .tenancyId("test-tenant")
                                                             .build();

        WorkItem compensating = service.compensate(original.id(), compReq, "operator-1", "Trial withdrawn");

        assertThat(compensating).isNotNull();
        assertThat(compensating.compensatesWorkItemId()).isEqualTo(original.id());
        assertThat(compensating.status()).isEqualTo(WorkItemStatus.PENDING);

        WorkItem updatedOriginal = service.findById(original.id()).orElseThrow();
        assertThat(updatedOriginal.compensationStatus()).isEqualTo(CompensationStatus.COMPENSATING);
    }

    @Test
    void compensate_pendingWorkItem_throws() {
        WorkItem pending = service.create(basicRequest());
        WorkItemCreateRequest compReq = WorkItemCreateRequest.builder()
                                                             .title("Compensate").candidateGroups("g").createdBy("op").tenancyId("test-tenant").build();

        assertThatThrownBy(() -> service.compensate(pending.id(), compReq, "op", "reason"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED");
    }

    @Test
    void compensate_alreadyCompensating_throws() {
        WorkItem original = createCompletedWorkItem();
        WorkItemCreateRequest compReq = WorkItemCreateRequest.builder()
                                                             .title("Compensate").candidateGroups("g").createdBy("op").tenancyId("test-tenant").build();
        service.compensate(original.id(), compReq, "op", "reason");

        assertThatThrownBy(() -> service.compensate(original.id(), compReq, "op", "again"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("compensation activity");
    }

    @Test
    void compensate_compensatingWorkItem_throws() {
        WorkItem original = createCompletedWorkItem();
        WorkItemCreateRequest compReq = WorkItemCreateRequest.builder()
                                                             .title("Compensate").candidateGroups("g").createdBy("op").tenancyId("test-tenant").build();
        WorkItem compensating = service.compensate(original.id(), compReq, "op", "reason");

        service.claim(compensating.id(), "bob");
        service.start(compensating.id(), "bob");
        service.complete(compensating.id(), "bob", "reversed", null);

        assertThatThrownBy(() -> service.compensate(compensating.id(), compReq, "op", "meta"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Compensating WorkItems cannot");
    }

    @Test
    void markCompensated_setsStatusToCompensated() {
        WorkItem original = createCompletedWorkItem();
        WorkItemCreateRequest compReq = WorkItemCreateRequest.builder()
                                                             .title("Compensate").candidateGroups("g").createdBy("op").tenancyId("test-tenant").build();
        service.compensate(original.id(), compReq, "op", "reason");

        service.markCompensated(original.id());

        WorkItem updated = service.findById(original.id()).orElseThrow();
        assertThat(updated.compensationStatus()).isEqualTo(CompensationStatus.COMPENSATED);
    }

    @Test
    void compensate_writesAuditEntry() {
        WorkItem original = createCompletedWorkItem();
        WorkItemCreateRequest compReq = WorkItemCreateRequest.builder()
                                                             .title("Compensate").candidateGroups("g").createdBy("op").tenancyId("test-tenant").build();
        service.compensate(original.id(), compReq, "op", "trial withdrawn");

        List<AuditEntry> trail = auditStore.findByWorkItemId(original.id());
        assertThat(trail).anyMatch(e -> e.event.equals("COMPENSATION_STARTED"));
    }
}
