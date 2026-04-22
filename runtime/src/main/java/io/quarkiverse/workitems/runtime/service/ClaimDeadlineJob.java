package io.quarkiverse.workitems.runtime.service;

import java.time.Instant;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.workitems.runtime.event.WorkItemLifecycleEvent;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.repository.WorkItemQuery;
import io.quarkiverse.workitems.runtime.repository.WorkItemStore;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class ClaimDeadlineJob {

    @Inject
    WorkItemStore workItemStore;

    @Inject
    @ClaimEscalation
    EscalationPolicy claimEscalationPolicy;

    @Inject
    Event<WorkItemLifecycleEvent> lifecycleEvent;

    @Scheduled(every = "${quarkus.workitems.cleanup.expiry-check-seconds}s")
    @Transactional
    public void checkUnclaimedPastDeadline() {
        final Instant now = Instant.now();
        final List<WorkItem> unclaimed = workItemStore.scan(WorkItemQuery.claimExpired(now));
        for (final WorkItem item : unclaimed) {
            claimEscalationPolicy.onUnclaimedPastDeadline(item);
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("ESCALATED", item, "system", null));
        }
    }
}
