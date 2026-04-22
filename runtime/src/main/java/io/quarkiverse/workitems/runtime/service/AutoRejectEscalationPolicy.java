package io.quarkiverse.workitems.runtime.service;

import java.time.Instant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkiverse.work.api.EscalationPolicy;
import io.quarkiverse.work.api.WorkEventType;
import io.quarkiverse.work.api.WorkLifecycleEvent;
import io.quarkiverse.workitems.runtime.model.AuditEntry;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemStatus;
import io.quarkiverse.workitems.runtime.repository.AuditEntryStore;
import io.quarkiverse.workitems.runtime.repository.WorkItemStore;

@ApplicationScoped
public class AutoRejectEscalationPolicy implements EscalationPolicy {

    @Inject
    AuditEntryStore auditStore;

    @Inject
    WorkItemStore workItemStore;

    @Override
    public void escalate(final WorkLifecycleEvent event) {
        if (event.eventType() == WorkEventType.CLAIM_EXPIRED) {
            // Auto-reject only fires on expiry, not claim deadline breach
            // Claim deadline breach uses the claim escalation policy separately
            return;
        }
        final WorkItem workItem = (WorkItem) event.source();
        workItem.status = WorkItemStatus.REJECTED;
        workItem.completedAt = Instant.now();
        workItemStore.put(workItem);

        final AuditEntry entry = new AuditEntry();
        entry.workItemId = workItem.id;
        entry.event = "REJECTED";
        entry.actor = "system";
        entry.detail = "auto-rejected: expiry deadline exceeded";
        entry.occurredAt = Instant.now();
        auditStore.append(entry);
    }
}
