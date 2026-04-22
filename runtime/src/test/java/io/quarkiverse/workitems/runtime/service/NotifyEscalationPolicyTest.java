package io.quarkiverse.workitems.runtime.service;

import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.event.Event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkiverse.work.api.WorkEventType;
import io.quarkiverse.work.api.WorkLifecycleEvent;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemStatus;

@ExtendWith(MockitoExtension.class)
class NotifyEscalationPolicyTest {

    @Mock
    Event<WorkItemExpiredEvent> expiredEvent;

    private WorkLifecycleEvent makeEvent(final WorkEventType type, final WorkItem workItem) {
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

    @Test
    void escalate_expired_firesExpiredEvent() {
        final var policy = new NotifyEscalationPolicy(expiredEvent);
        final var wi = new WorkItem();
        wi.id = UUID.randomUUID();
        wi.status = WorkItemStatus.EXPIRED;
        policy.escalate(makeEvent(WorkEventType.EXPIRED, wi));
        verify(expiredEvent).fire(new WorkItemExpiredEvent(wi.id, wi.status));
    }

    @Test
    void escalate_claimExpired_doesNotFireExpiredEvent() {
        final var policy = new NotifyEscalationPolicy(expiredEvent);
        final var wi = new WorkItem();
        wi.id = UUID.randomUUID();
        wi.status = WorkItemStatus.PENDING;
        policy.escalate(makeEvent(WorkEventType.CLAIM_EXPIRED, wi));
        // CLAIM_EXPIRED just logs — no CDI event fired
        org.mockito.Mockito.verifyNoInteractions(expiredEvent);
    }
}
