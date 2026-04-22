package io.quarkiverse.workitems.filterregistry.engine;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkiverse.workitems.filterregistry.spi.*;
import io.quarkiverse.workitems.runtime.event.WorkItemLifecycleEvent;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemStatus;
import io.quarkiverse.workitems.runtime.repository.WorkItemStore;

@ExtendWith(MockitoExtension.class)
class FilterRegistryEngineTest {

    @Mock
    WorkItemStore workItemStore;
    @Mock
    FilterAction mockAction;

    private FilterRegistryEngine engine;
    private WorkItem workItem;

    @BeforeEach
    void setUp() {
        workItem = new WorkItem();
        workItem.id = UUID.randomUUID();
        workItem.confidenceScore = 0.55;
        workItem.category = "finance";
        when(workItemStore.get(workItem.id)).thenReturn(java.util.Optional.of(workItem));
        // lenient: type() is only called when action dispatch is reached;
        // disabled-filter, wrong-event, and unknown-action tests never reach dispatch
        lenient().when(mockAction.type()).thenReturn("TEST_ACTION");
        engine = new FilterRegistryEngine(workItemStore, new JexlConditionEvaluator(),
                List.of(mockAction));
    }

    @Test
    void onEvent_appliesAction_whenConditionMatches() {
        final FilterDefinition def = FilterDefinition.onAdd("test", "desc", true,
                "workItem.confidenceScore < threshold",
                Map.of("threshold", 0.7),
                List.of(ActionDescriptor.of("TEST_ACTION", Map.of("key", "val"))));

        engine.processEvent(createdEvent(), List.of(def));

        verify(mockAction).apply(eq(workItem), eq(Map.of("key", "val")));
    }

    @Test
    void onEvent_skipsAction_whenConditionDoesNotMatch() {
        final FilterDefinition def = FilterDefinition.onAdd("test", "desc", true,
                "workItem.confidenceScore > 0.9",
                Map.of(),
                List.of(ActionDescriptor.of("TEST_ACTION", Map.of())));

        engine.processEvent(createdEvent(), List.of(def));

        verify(mockAction, never()).apply(any(), any());
    }

    @Test
    void onEvent_skipsDisabledFilter() {
        final FilterDefinition def = FilterDefinition.onAdd("test", "desc", false,
                "workItem.confidenceScore < threshold",
                Map.of("threshold", 0.7),
                List.of(ActionDescriptor.of("TEST_ACTION", Map.of())));

        engine.processEvent(createdEvent(), List.of(def));

        verify(mockAction, never()).apply(any(), any());
    }

    @Test
    void onEvent_skipsFilter_whenEventTypeNotSubscribed() {
        final FilterDefinition def = new FilterDefinition("test", "desc", true,
                Set.of(FilterEvent.REMOVE), "true", Map.of(),
                List.of(ActionDescriptor.of("TEST_ACTION", Map.of())));

        engine.processEvent(createdEvent(), List.of(def));

        verify(mockAction, never()).apply(any(), any());
    }

    @Test
    void onEvent_skipsUnknownActionType_withoutThrowing() {
        final FilterDefinition def = FilterDefinition.onAdd("test", "desc", true,
                "true", Map.of(),
                List.of(ActionDescriptor.of("UNKNOWN_ACTION", Map.of())));

        // Should not throw
        engine.processEvent(createdEvent(), List.of(def));
    }

    @Test
    void onEvent_mapsTerminalStatusToRemoveEvent() {
        // COMPLETED is a terminal event → FilterEvent.REMOVE
        final FilterDefinition def = new FilterDefinition("test", "desc", true,
                Set.of(FilterEvent.REMOVE), "true", Map.of(),
                List.of(ActionDescriptor.of("TEST_ACTION", Map.of())));

        workItem.status = WorkItemStatus.COMPLETED;
        final WorkItemLifecycleEvent completedEvent = WorkItemLifecycleEvent.of(
                "COMPLETED", workItem, "reviewer", null);

        engine.processEvent(completedEvent, List.of(def));

        verify(mockAction).apply(eq(workItem), any());
    }

    @Test
    void onEvent_mapsNonCreatedNonTerminalEventToUpdateEvent() {
        // ASSIGNED is not created or terminal → FilterEvent.UPDATE
        final FilterDefinition def = new FilterDefinition("test", "desc", true,
                Set.of(FilterEvent.UPDATE), "true", Map.of(),
                List.of(ActionDescriptor.of("TEST_ACTION", Map.of())));

        workItem.status = WorkItemStatus.ASSIGNED;
        final WorkItemLifecycleEvent assignedEvent = WorkItemLifecycleEvent.of(
                "ASSIGNED", workItem, "reviewer", null);

        engine.processEvent(assignedEvent, List.of(def));

        verify(mockAction).apply(eq(workItem), any());
    }

    private WorkItemLifecycleEvent createdEvent() {
        workItem.status = WorkItemStatus.PENDING;
        return WorkItemLifecycleEvent.of("CREATED", workItem, "system", null);
    }
}
