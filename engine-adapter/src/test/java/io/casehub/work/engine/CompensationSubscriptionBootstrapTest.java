package io.casehub.work.engine;

import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.subscription.EventTypeDescriptor;
import io.casehub.platform.api.subscription.EventTypeRegistry;
import io.casehub.platform.api.subscription.Subscription;
import io.casehub.platform.api.subscription.SubscriptionInput;
import io.casehub.platform.api.subscription.SubscriptionStore;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CompensationSubscriptionBootstrapTest {

    @Test
    void registersAllFiveSubscriptions() {
        var store = mock(SubscriptionStore.class);
        when(store.findAllEnabled()).thenReturn(Stream.empty());
        var eventRegistry = mock(EventTypeRegistry.class);
        var bootstrap = buildBootstrap(store, eventRegistry);

        bootstrap.onStartup(mock(StartupEvent.class));

        var captor = ArgumentCaptor.forClass(SubscriptionInput.class);
        verify(store, times(5)).store(captor.capture());
        var eventTypes = captor.getAllValues().stream()
                .map(SubscriptionInput::eventType).toList();
        assertThat(eventTypes).containsExactlyInAnyOrder(
                "io.casehub.work.workitem.compensation_started",
                "io.casehub.work.workitem.compensation_completed",
                "io.casehub.engine.case.compensation.started",
                "io.casehub.engine.case.compensation.completed",
                "io.casehub.engine.case.compensation.faulted");
    }

    @Test
    void skipsAlreadyRegisteredSubscriptions() {
        var existing = mock(Subscription.class);
        when(existing.eventType()).thenReturn("io.casehub.engine.case.compensation.started");
        var store = mock(SubscriptionStore.class);
        when(store.findAllEnabled()).thenReturn(Stream.of(existing));
        var eventRegistry = mock(EventTypeRegistry.class);
        var bootstrap = buildBootstrap(store, eventRegistry);

        bootstrap.onStartup(mock(StartupEvent.class));

        var captor = ArgumentCaptor.forClass(SubscriptionInput.class);
        verify(store, times(4)).store(captor.capture());
        var eventTypes = captor.getAllValues().stream()
                .map(SubscriptionInput::eventType).toList();
        assertThat(eventTypes).doesNotContain("io.casehub.engine.case.compensation.started");
    }

    @Test
    void registersThreeCaseEventTypeDescriptors() {
        var store = mock(SubscriptionStore.class);
        when(store.findAllEnabled()).thenReturn(Stream.empty());
        var eventRegistry = mock(EventTypeRegistry.class);
        var bootstrap = buildBootstrap(store, eventRegistry);

        bootstrap.onStartup(mock(StartupEvent.class));

        var captor = ArgumentCaptor.forClass(EventTypeDescriptor.class);
        verify(eventRegistry, times(3)).register(captor.capture());
        var eventTypes = captor.getAllValues().stream()
                .map(EventTypeDescriptor::eventType).toList();
        assertThat(eventTypes).containsExactlyInAnyOrder(
                "io.casehub.engine.case.compensation.started",
                "io.casehub.engine.case.compensation.completed",
                "io.casehub.engine.case.compensation.faulted");
    }

    @Test
    void caseCompensationStarted_hasUrgentSeverity() {
        var store = mock(SubscriptionStore.class);
        when(store.findAllEnabled()).thenReturn(Stream.empty());
        var eventRegistry = mock(EventTypeRegistry.class);
        var bootstrap = buildBootstrap(store, eventRegistry);

        bootstrap.onStartup(mock(StartupEvent.class));

        var captor = ArgumentCaptor.forClass(SubscriptionInput.class);
        verify(store, times(5)).store(captor.capture());
        var started = captor.getAllValues().stream()
                .filter(s -> s.eventType().equals("io.casehub.engine.case.compensation.started"))
                .findFirst().orElseThrow();
        assertThat(started.template().severity()).isEqualTo(NotificationSeverity.URGENT);
    }

    @Test
    void caseCompensationFaulted_hasUrgentSeverity() {
        var store = mock(SubscriptionStore.class);
        when(store.findAllEnabled()).thenReturn(Stream.empty());
        var eventRegistry = mock(EventTypeRegistry.class);
        var bootstrap = buildBootstrap(store, eventRegistry);

        bootstrap.onStartup(mock(StartupEvent.class));

        var captor = ArgumentCaptor.forClass(SubscriptionInput.class);
        verify(store, times(5)).store(captor.capture());
        var faulted = captor.getAllValues().stream()
                .filter(s -> s.eventType().equals("io.casehub.engine.case.compensation.faulted"))
                .findFirst().orElseThrow();
        assertThat(faulted.template().severity()).isEqualTo(NotificationSeverity.URGENT);
    }

    @Test
    void workCompensationCompleted_hasInfoSeverity() {
        var store = mock(SubscriptionStore.class);
        when(store.findAllEnabled()).thenReturn(Stream.empty());
        var eventRegistry = mock(EventTypeRegistry.class);
        var bootstrap = buildBootstrap(store, eventRegistry);

        bootstrap.onStartup(mock(StartupEvent.class));

        var captor = ArgumentCaptor.forClass(SubscriptionInput.class);
        verify(store, times(5)).store(captor.capture());
        var completed = captor.getAllValues().stream()
                .filter(s -> s.eventType().equals("io.casehub.work.workitem.compensation_completed"))
                .findFirst().orElseThrow();
        assertThat(completed.template().severity()).isEqualTo(NotificationSeverity.INFO);
    }

    @Test
    void noOp_whenStoreUnsatisfied() {
        var bootstrap = new CompensationSubscriptionBootstrap();
        setField(bootstrap, "subscriptionStoreInstance", unsatisfiedInstance());
        setField(bootstrap, "eventTypeRegistryInstance", unsatisfiedInstance());

        bootstrap.onStartup(mock(StartupEvent.class));
    }

    @Test
    void handlesStoreFailureGracefully() {
        var store = mock(SubscriptionStore.class);
        when(store.findAllEnabled()).thenReturn(Stream.empty());
        when(store.store(any())).thenThrow(new RuntimeException("DB down"));
        var eventRegistry = mock(EventTypeRegistry.class);
        var bootstrap = buildBootstrap(store, eventRegistry);

        bootstrap.onStartup(mock(StartupEvent.class));
    }

    private CompensationSubscriptionBootstrap buildBootstrap(
            SubscriptionStore store, EventTypeRegistry eventRegistry) {
        var bootstrap = new CompensationSubscriptionBootstrap();
        setField(bootstrap, "subscriptionStoreInstance", satisfiedInstance(store));
        setField(bootstrap, "eventTypeRegistryInstance", satisfiedInstance(eventRegistry));
        return bootstrap;
    }

    @SuppressWarnings("unchecked")
    private <T> Instance<T> satisfiedInstance(T value) {
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

    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
