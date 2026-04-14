package io.quarkiverse.tarkus.runtime.service;

import java.time.Instant;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.tarkus.runtime.event.WorkItemLifecycleEvent;
import io.quarkiverse.tarkus.runtime.model.AuditEntry;
import io.quarkiverse.tarkus.runtime.model.WorkItem;
import io.quarkiverse.tarkus.runtime.model.WorkItemStatus;
import io.quarkiverse.tarkus.runtime.repository.AuditEntryRepository;
import io.quarkiverse.tarkus.runtime.repository.WorkItemRepository;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class ExpiryCleanupJob {

    @Inject
    WorkItemRepository workItemRepo;

    @Inject
    AuditEntryRepository auditRepo;

    @Inject
    @ExpiryEscalation
    EscalationPolicy escalationPolicy;

    @Inject
    Event<WorkItemLifecycleEvent> lifecycleEvent;

    @Scheduled(every = "${quarkus.tarkus.cleanup.expiry-check-seconds}s")
    @Transactional
    public void checkExpired() {
        final Instant now = Instant.now();
        final List<WorkItem> expired = workItemRepo.findExpired(now);
        for (final WorkItem item : expired) {
            item.status = WorkItemStatus.EXPIRED;
            item.completedAt = now;
            workItemRepo.save(item);

            final AuditEntry entry = new AuditEntry();
            entry.workItemId = item.id;
            entry.event = "EXPIRED";
            entry.actor = "system";
            entry.occurredAt = now;
            auditRepo.append(entry);
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("EXPIRED", item.id, item.status, "system", null));

            escalationPolicy.onExpired(item);
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("ESCALATED", item.id, item.status, "system", null));
        }
    }
}
