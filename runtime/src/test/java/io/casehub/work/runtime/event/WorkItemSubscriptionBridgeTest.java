package io.casehub.work.runtime.event;

import io.casehub.platform.api.datasource.DataSource;
import io.casehub.platform.api.datasource.DataSourceRegistry;
import io.casehub.platform.api.path.Path;
import io.casehub.work.api.WorkItem;
import io.casehub.work.api.WorkItemLifecycleEvent;
import io.casehub.work.api.WorkItemStatus;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static io.casehub.platform.api.identity.TenancyConstants.PLATFORM_TENANT_ID;
import static io.casehub.platform.api.subscription.SubscriptionConstants.NOTIFICATION_DATASOURCE_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkItemSubscriptionBridgeTest {

    @Test
    void onWorkItemEvent_insertsIntoDataSource() {
        var added = new ArrayList<>();
        @SuppressWarnings("unchecked")
        DataSource<Object> ds = mock(DataSource.class);
        doAnswer(inv -> { added.add(inv.getArgument(0)); return null; })
                .when(ds).add(any());

        var registry = mock(DataSourceRegistry.class);
        when(registry.resolveSource(eq(NOTIFICATION_DATASOURCE_PATH), eq(PLATFORM_TENANT_ID)))
                .thenReturn(Optional.of(ds));

        var bridge = new WorkItemSubscriptionBridge();
        setField(bridge, "dataSourceRegistryInstance", satisfiedInstance(registry));

        var event = sampleEvent("COMPLETED");
        bridge.onWorkItemEvent(event);

        assertThat(added).hasSize(1);
        assertThat(added.get(0)).isSameAs(event);
    }

    @Test
    void onWorkItemEvent_noOpWhenRegistryUnsatisfied() {
        var bridge = new WorkItemSubscriptionBridge();
        setField(bridge, "dataSourceRegistryInstance", unsatisfiedInstance());

        bridge.onWorkItemEvent(sampleEvent("CREATED"));
    }

    @Test
    void onWorkItemEvent_catchesAndLogsExceptions() {
        var registry = mock(DataSourceRegistry.class);
        when(registry.resolveSource(any(Path.class), any(String.class)))
                .thenThrow(new RuntimeException("DataSource unavailable"));

        var bridge = new WorkItemSubscriptionBridge();
        setField(bridge, "dataSourceRegistryInstance", satisfiedInstance(registry));

        bridge.onWorkItemEvent(sampleEvent("ASSIGNED"));
    }

    @Test
    void emittedEventTypes_includesCompensationTypes() {
        var eventRegistry = mock(io.casehub.platform.api.subscription.EventTypeRegistry.class);
        var bridge        = new WorkItemSubscriptionBridge();
        setField(bridge, "dataSourceRegistryInstance", unsatisfiedInstance());
        setField(bridge, "eventTypeRegistryInstance", satisfiedInstance(eventRegistry));

        bridge.onStartup(mock(io.quarkus.runtime.StartupEvent.class));

        var captor = org.mockito.ArgumentCaptor.forClass(
                io.casehub.platform.api.subscription.EventTypeDescriptor.class);
        verify(eventRegistry, atLeastOnce()).register(captor.capture());
        var registeredTypes = captor.getAllValues().stream()
                                    .map(io.casehub.platform.api.subscription.EventTypeDescriptor::eventType)
                                    .toList();
        assertThat(registeredTypes).contains(
                "io.casehub.work.workitem.compensation_started",
                "io.casehub.work.workitem.compensation_completed");
    }


    private WorkItemLifecycleEvent sampleEvent(final String name) {
        var wi = WorkItem.builder()
                .id(UUID.randomUUID())
                .status(WorkItemStatus.IN_PROGRESS)
                .tenancyId("test-tenant")
                .build();
        return WorkItemLifecycleEvent.of(name, wi, "test", null);
    }

    @SuppressWarnings("unchecked")
    private <T> Instance<T> satisfiedInstance(final T value) {
        var instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(value);
        return instance;
    }

    @SuppressWarnings("unchecked")
    private <T> Instance<T> unsatisfiedInstance() {
        var instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(true);
        return instance;
    }

    private void setField(final Object target, final String fieldName, final Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
