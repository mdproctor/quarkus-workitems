package io.quarkiverse.workitems.runtime.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkiverse.work.api.EscalationPolicy;
import io.quarkiverse.work.api.WorkEventType;
import io.quarkiverse.work.api.WorkLifecycleEvent;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemStatus;
import io.quarkiverse.workitems.runtime.repository.WorkItemStore;

@ApplicationScoped
public class ReassignEscalationPolicy implements EscalationPolicy {

    @Inject
    WorkItemStore workItemStore;

    @Inject
    NotifyEscalationPolicy notifyPolicy;

    @Override
    public void escalate(final WorkLifecycleEvent event) {
        final WorkItem workItem = (WorkItem) event.source();
        if (event.eventType() == WorkEventType.CLAIM_EXPIRED) {
            if (hasCandidates(workItem)) {
                workItem.assigneeId = null;
                // status stays PENDING — already unclaimed
                workItemStore.put(workItem);
            } else {
                notifyPolicy.escalate(event);
            }
        } else {
            if (hasCandidates(workItem)) {
                workItem.assigneeId = null;
                workItem.status = WorkItemStatus.PENDING;
                workItemStore.put(workItem);
            } else {
                notifyPolicy.escalate(event);
            }
        }
    }

    private boolean hasCandidates(final WorkItem workItem) {
        return (workItem.candidateGroups != null && !workItem.candidateGroups.isBlank())
                || (workItem.candidateUsers != null && !workItem.candidateUsers.isBlank());
    }
}
