package io.casehub.work.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkEventTypeTest {

    @Test
    void allExpectedValuesExist() {
        assertThat(WorkEventType.values()).extracting(Enum::name)
                                          .containsExactlyInAnyOrder(
                                                  "CREATED", "ASSIGNED", "STARTED", "COMPLETED", "REJECTED", "FAULTED",
                                                  "DELEGATED", "DELEGATION_ACCEPTED", "DELEGATION_DECLINED", "RELEASED",
                                                  "SUSPENDED", "RESUMED", "CANCELLED", "OBSOLETE", "EXPIRED", "CLAIM_EXPIRED",
                                                  "SPAWNED", "ESCALATED", "DEADLINE_EXTENDED", "SLA_REASSIGNED", "SLA_EXTENDED",
                                                  "SIGNAL_RECEIVED", "MANUALLY_ESCALATED", "PROGRESS_UPDATE", "LABEL_ADDED", "LABEL_REMOVED",
                                                  "COMPENSATION_STARTED", "COMPENSATION_COMPLETED");}

    @Test
    void faulted_isAccessible() {
        assertThat(WorkEventType.valueOf("FAULTED")).isEqualTo(WorkEventType.FAULTED);
    }

    @Test
    void obsolete_isAccessible() {
        assertThat(WorkEventType.valueOf("OBSOLETE")).isEqualTo(WorkEventType.OBSOLETE);
    }

    @Test
    void delegationAccepted_isAccessible() {
        assertThat(WorkEventType.valueOf("DELEGATION_ACCEPTED")).isEqualTo(WorkEventType.DELEGATION_ACCEPTED);
    }

    @Test
    void delegationDeclined_isAccessible() {
        assertThat(WorkEventType.valueOf("DELEGATION_DECLINED")).isEqualTo(WorkEventType.DELEGATION_DECLINED);
    }

    @Test
    void slaReassigned_isAccessible() {
        assertThat(WorkEventType.valueOf("SLA_REASSIGNED")).isEqualTo(WorkEventType.SLA_REASSIGNED);
    }

    @Test
    void slaExtended_isAccessible() {
        assertThat(WorkEventType.valueOf("SLA_EXTENDED")).isEqualTo(WorkEventType.SLA_EXTENDED);
    }

    @Test
    void progressUpdate_isAccessible() {
        assertThat(WorkEventType.valueOf("PROGRESS_UPDATE")).isEqualTo(WorkEventType.PROGRESS_UPDATE);
    }

    @Test
    void labelAdded_isAccessible() {
        assertThat(WorkEventType.valueOf("LABEL_ADDED")).isEqualTo(WorkEventType.LABEL_ADDED);
    }

    @Test
    void labelRemoved_isAccessible() {
        assertThat(WorkEventType.valueOf("LABEL_REMOVED")).isEqualTo(WorkEventType.LABEL_REMOVED);
    }

    @Test
    void concreteEvent_implementsAbstractMethods() {
        final WorkItemRef ref = new WorkItemRef(UUID.randomUUID(), WorkItemStatus.PENDING,
                null, null, null, null, null, null, null, null, null);
        var event = new WorkItemEvent() {
            @Override public WorkItemRef ref() { return ref; }
            @Override public WorkEventType eventType() { return WorkEventType.CREATED; }
            @Override public java.time.Instant occurredAt() { return java.time.Instant.now(); }
            @Override public String actor() { return "test"; }
            @Override public String detail() { return null; }
        };
        assertThat(event.eventType()).isEqualTo(WorkEventType.CREATED);
        assertThat(event.ref()).isNotNull();
    }
}
