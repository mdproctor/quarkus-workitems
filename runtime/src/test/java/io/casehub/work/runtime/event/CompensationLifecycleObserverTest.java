package io.casehub.work.runtime.event;

import io.casehub.work.api.CompensationStatus;
import io.casehub.work.api.WorkItem;
import io.casehub.work.api.WorkItemLifecycleEvent;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.runtime.service.WorkItemService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompensationLifecycleObserverTest {

    @Mock
    WorkItemService workItemService;
    @InjectMocks
    CompensationLifecycleObserver observer;

    @Test
    void completedCompensatingWorkItem_marksOriginalCompensated() {
        UUID originalId = UUID.randomUUID();
        UUID compensatingId = UUID.randomUUID();
        WorkItem compensatingItem = WorkItem.builder()
                .id(compensatingId)
                .status(WorkItemStatus.COMPLETED)
                .compensatesWorkItemId(originalId)
                .compensationStatus(CompensationStatus.NONE)
                .build();
        WorkItemLifecycleEvent event = WorkItemLifecycleEvent.of(
                "COMPLETED", compensatingItem, "worker-1", "done");

        observer.onWorkItemLifecycle(event);

        verify(workItemService).markCompensated(originalId);
    }

    @Test
    void completedRegularWorkItem_doesNotMarkCompensated() {
        UUID regularId = UUID.randomUUID();
        WorkItem regularItem = WorkItem.builder()
                .id(regularId)
                .status(WorkItemStatus.COMPLETED)
                .compensationStatus(CompensationStatus.NONE)
                .build();
        WorkItemLifecycleEvent event = WorkItemLifecycleEvent.of(
                "COMPLETED", regularItem, "worker-1", "done");

        observer.onWorkItemLifecycle(event);

        verify(workItemService, never()).markCompensated(any());
    }

    @Test
    void nonCompletedCompensatingWorkItem_doesNotMarkCompensated() {
        UUID originalId = UUID.randomUUID();
        UUID compensatingId = UUID.randomUUID();
        WorkItem compensatingItem = WorkItem.builder()
                .id(compensatingId)
                .status(WorkItemStatus.IN_PROGRESS)
                .compensatesWorkItemId(originalId)
                .compensationStatus(CompensationStatus.NONE)
                .build();
        WorkItemLifecycleEvent event = WorkItemLifecycleEvent.of(
                "STARTED", compensatingItem, "worker-1", null);

        observer.onWorkItemLifecycle(event);

        verify(workItemService, never()).markCompensated(any());
    }
}
