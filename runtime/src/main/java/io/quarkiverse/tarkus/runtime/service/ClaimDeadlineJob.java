package io.quarkiverse.tarkus.runtime.service;

import java.time.Instant;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.tarkus.runtime.event.WorkItemLifecycleEvent;
import io.quarkiverse.tarkus.runtime.model.WorkItem;
import io.quarkiverse.tarkus.runtime.repository.WorkItemRepository;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class ClaimDeadlineJob {

    @Inject
    WorkItemRepository workItemRepo;

    @Inject
    @ClaimEscalation
    EscalationPolicy claimEscalationPolicy;

    @Inject
    Event<WorkItemLifecycleEvent> lifecycleEvent;

    @Scheduled(every = "${quarkus.tarkus.cleanup.expiry-check-seconds}s")
    @Transactional
    public void checkUnclaimedPastDeadline() {
        final Instant now = Instant.now();
        final List<WorkItem> unclaimed = workItemRepo.findUnclaimedPastDeadline(now);
        for (final WorkItem item : unclaimed) {
            claimEscalationPolicy.onUnclaimedPastDeadline(item);
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("ESCALATED", item.id, item.status, "system", null));
        }
    }
}
