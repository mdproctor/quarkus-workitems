package io.quarkiverse.workitems.runtime.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkiverse.work.api.EscalationPolicy;
import io.quarkiverse.work.api.WorkEventType;
import io.quarkiverse.work.api.WorkLifecycleEvent;
import io.quarkiverse.workitems.runtime.model.WorkItem;

@ApplicationScoped
public class NotifyEscalationPolicy implements EscalationPolicy {

    private static final Logger LOG = Logger.getLogger(NotifyEscalationPolicy.class);

    private final Event<WorkItemExpiredEvent> expiredEvent;

    @Inject
    public NotifyEscalationPolicy(final Event<WorkItemExpiredEvent> expiredEvent) {
        this.expiredEvent = expiredEvent;
    }

    @Override
    public void escalate(final WorkLifecycleEvent event) {
        final WorkItem workItem = (WorkItem) event.source();
        if (event.eventType() == WorkEventType.CLAIM_EXPIRED) {
            LOG.warnf("WorkItem %s unclaimed past deadline", workItem.id);
        } else {
            LOG.warnf("WorkItem %s expired (was %s)", workItem.id, workItem.status);
            expiredEvent.fire(new WorkItemExpiredEvent(workItem.id, workItem.status));
        }
    }
}
