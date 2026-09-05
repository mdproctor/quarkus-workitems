package io.casehub.work.runtime.event;

import io.casehub.work.api.WorkItemLifecycleEvent;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.runtime.service.WorkItemService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class CompensationLifecycleObserver {

    @Inject
    WorkItemService workItemService;

    void onWorkItemLifecycle(@Observes WorkItemLifecycleEvent event) {
        if (event.status() != WorkItemStatus.COMPLETED) {
            return;
        }
        if (event.workItem() != null && event.workItem().compensatesWorkItemId() != null) {
            workItemService.markCompensated(event.workItem().compensatesWorkItemId());
        }
    }
}
