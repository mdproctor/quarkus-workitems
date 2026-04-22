package io.quarkiverse.workitems.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.work.api.EscalationPolicy;
import io.quarkiverse.work.api.WorkEventType;
import io.quarkiverse.work.api.WorkLifecycleEvent;
import io.quarkiverse.workitems.runtime.model.AuditEntry;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemPriority;
import io.quarkiverse.workitems.runtime.model.WorkItemStatus;
import io.quarkiverse.workitems.runtime.repository.AuditEntryStore;
import io.quarkiverse.workitems.runtime.repository.WorkItemQuery;
import io.quarkiverse.workitems.runtime.repository.WorkItemStore;

/**
 * Pure JUnit 5 unit tests for the three EscalationPolicy implementations.
 *
 * <p>
 * No Quarkus runtime — all CDI-injected fields are wired via reflection so that
 * the real policy classes are exercised without container startup overhead.
 */
class EscalationPolicyTest {

    // -------------------------------------------------------------------------
    // In-memory repository fakes (same pattern as WorkItemServiceTest)
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
            return new ArrayList<>(store.values());
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
        public List<AuditEntry> query(io.quarkiverse.workitems.runtime.repository.AuditQuery query) {
            return List.of();
        }

        @Override
        public long count(io.quarkiverse.workitems.runtime.repository.AuditQuery query) {
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Reflection helper — wire CDI @Inject fields without container
    // -------------------------------------------------------------------------

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    // -------------------------------------------------------------------------
    // Event helper — build a WorkLifecycleEvent for a given type + WorkItem
    // -------------------------------------------------------------------------

    private static WorkLifecycleEvent makeEvent(final WorkEventType type, final WorkItem workItem) {
        return new WorkLifecycleEvent() {
            @Override
            public WorkEventType eventType() {
                return type;
            }

            @Override
            public Map<String, Object> context() {
                return Map.of();
            }

            @Override
            public Object source() {
                return workItem;
            }
        };
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private TestWorkItemRepo workItemRepo;
    private TestAuditRepo auditRepo;

    private NotifyEscalationPolicy notifyPolicy;
    private AutoRejectEscalationPolicy autoRejectPolicy;
    private ReassignEscalationPolicy reassignPolicy;

    @BeforeEach
    void setUp() throws Exception {
        workItemRepo = new TestWorkItemRepo();
        auditRepo = new TestAuditRepo();

        // NotifyEscalationPolicy — expiredEvent left null; escalate(EXPIRED) fires it,
        // but the status-only tests guard via try/catch. Tests for CLAIM_EXPIRED do not fire it.
        notifyPolicy = new NotifyEscalationPolicy(null);

        autoRejectPolicy = new AutoRejectEscalationPolicy();
        inject(autoRejectPolicy, "workItemStore", workItemRepo);
        inject(autoRejectPolicy, "auditStore", auditRepo);

        reassignPolicy = new ReassignEscalationPolicy();
        inject(reassignPolicy, "workItemStore", workItemRepo);
        inject(reassignPolicy, "notifyPolicy", notifyPolicy);
    }

    private WorkItem workItem(WorkItemStatus status) {
        WorkItem wi = new WorkItem();
        wi.id = UUID.randomUUID();
        wi.title = "Test item";
        wi.status = status;
        wi.priority = WorkItemPriority.NORMAL;
        wi.createdAt = Instant.now();
        wi.updatedAt = Instant.now();
        workItemRepo.put(wi);
        return wi;
    }

    // -------------------------------------------------------------------------
    // NotifyEscalationPolicy
    // -------------------------------------------------------------------------

    @Test
    void notifyPolicy_escalate_expired_doesNotChangeStatus() {
        WorkItem wi = workItem(WorkItemStatus.EXPIRED);
        // notifyPolicy logs and fires CDI event — expiredEvent is null so we guard
        // the status-only assertion without relying on CDI event delivery
        try {
            notifyPolicy.escalate(makeEvent(WorkEventType.EXPIRED, wi));
        } catch (NullPointerException ignored) {
            // CDI Event<> not wired in pure JUnit context — expected; status is unchanged
        }
        assertThat(wi.status).isEqualTo(WorkItemStatus.EXPIRED);
    }

    @Test
    void notifyPolicy_escalate_claimExpired_doesNotChangeStatus() {
        WorkItem wi = workItem(WorkItemStatus.PENDING);
        // CLAIM_EXPIRED only logs — no CDI event fired, no NPE
        notifyPolicy.escalate(makeEvent(WorkEventType.CLAIM_EXPIRED, wi));
        assertThat(wi.status).isEqualTo(WorkItemStatus.PENDING);
    }

    // -------------------------------------------------------------------------
    // AutoRejectEscalationPolicy
    // -------------------------------------------------------------------------

    @Test
    void autoRejectPolicy_escalate_expired_transitionsToRejected() {
        WorkItem wi = workItem(WorkItemStatus.EXPIRED);
        autoRejectPolicy.escalate(makeEvent(WorkEventType.EXPIRED, wi));
        assertThat(wi.status).isEqualTo(WorkItemStatus.REJECTED);
        assertThat(wi.completedAt).isNotNull();
    }

    @Test
    void autoRejectPolicy_escalate_expired_writesAuditEntry() {
        WorkItem wi = workItem(WorkItemStatus.EXPIRED);
        autoRejectPolicy.escalate(makeEvent(WorkEventType.EXPIRED, wi));
        List<AuditEntry> trail = auditRepo.findByWorkItemId(wi.id);
        assertThat(trail).anyMatch(e -> "REJECTED".equals(e.event) && "system".equals(e.actor));
    }

    @Test
    void autoRejectPolicy_escalate_claimExpired_doesNothing() {
        WorkItem wi = workItem(WorkItemStatus.PENDING);
        autoRejectPolicy.escalate(makeEvent(WorkEventType.CLAIM_EXPIRED, wi));
        assertThat(wi.status).isEqualTo(WorkItemStatus.PENDING);
    }

    // -------------------------------------------------------------------------
    // ReassignEscalationPolicy
    // -------------------------------------------------------------------------

    @Test
    void reassignPolicy_escalate_expired_withCandidateGroups_returnsToPending() {
        WorkItem wi = workItem(WorkItemStatus.ASSIGNED);
        wi.assigneeId = "alice";
        wi.candidateGroups = "team-a";
        workItemRepo.put(wi);

        reassignPolicy.escalate(makeEvent(WorkEventType.EXPIRED, wi));

        assertThat(wi.status).isEqualTo(WorkItemStatus.PENDING);
        assertThat(wi.assigneeId).isNull();
    }

    @Test
    void reassignPolicy_escalate_expired_withoutCandidateGroups_statusRemainsExpired() {
        WorkItem wi = workItem(WorkItemStatus.EXPIRED);
        wi.candidateGroups = null;
        wi.candidateUsers = null;
        workItemRepo.put(wi);

        // Falls back to notifyPolicy.escalate(EXPIRED) — CDI Event null causes NPE, status unchanged
        try {
            reassignPolicy.escalate(makeEvent(WorkEventType.EXPIRED, wi));
        } catch (NullPointerException ignored) {
            // Expected: notify fires CDI event that is not wired in pure JUnit context
        }

        assertThat(wi.status).isEqualTo(WorkItemStatus.EXPIRED);
    }

    @Test
    void reassignPolicy_escalate_claimExpired_withCandidateGroups_returnsToPending() {
        WorkItem wi = workItem(WorkItemStatus.PENDING);
        wi.assigneeId = "alice";
        wi.candidateGroups = "team-a";
        workItemRepo.put(wi);

        reassignPolicy.escalate(makeEvent(WorkEventType.CLAIM_EXPIRED, wi));

        assertThat(wi.status).isEqualTo(WorkItemStatus.PENDING);
        assertThat(wi.assigneeId).isNull();
    }
}
