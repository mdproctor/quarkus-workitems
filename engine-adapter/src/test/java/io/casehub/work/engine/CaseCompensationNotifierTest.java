package io.casehub.work.engine;

import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.platform.api.datasource.DataSource;
import io.casehub.platform.api.datasource.DataSourceRegistry;
import io.casehub.platform.api.path.Path;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static io.casehub.platform.api.identity.TenancyConstants.PLATFORM_TENANT_ID;
import static io.casehub.platform.api.subscription.SubscriptionConstants.NOTIFICATION_DATASOURCE_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CaseCompensationNotifierTest {

    @ParameterizedTest
    @CsvSource({
        "CaseCompensating,    STARTED",
        "CaseCompensated,     COMPLETED",
        "CaseCompensationFaulted, FAULTED"
    })
    void firesCompensationEvent_forCompensationEventTypes(String eventType, String expectedKind) {
        var captured = new ArrayList<Object>();
        var notifier = buildNotifier(captured);

        notifier.onCaseLifecycle(caseEvent(eventType, "tenant-1", "ClinicalTrial"));

        assertThat(captured).hasSize(1);
        var fired = (CaseCompensationEvent) captured.get(0);
        assertThat(fired.kind()).isEqualTo(CaseCompensationEvent.Kind.valueOf(expectedKind));
        assertThat(fired.tenancyId()).isEqualTo("tenant-1");
        assertThat(fired.caseDefinitionName()).isEqualTo("ClinicalTrial");
    }

    @Test
    void ignoresNonCompensationEventTypes() {
        var captured = new ArrayList<Object>();
        var notifier = buildNotifier(captured);

        notifier.onCaseLifecycle(caseEvent("CaseCompleted", "tenant-1", "Trial"));
        notifier.onCaseLifecycle(caseEvent("CaseFaulted", "tenant-1", "Trial"));
        notifier.onCaseLifecycle(caseEvent("CaseCancelled", "tenant-1", "Trial"));

        assertThat(captured).isEmpty();
    }

    @Test
    void noOp_whenRegistryUnsatisfied() {
        var notifier = new CaseCompensationNotifier();
        setField(notifier, "dataSourceRegistryInstance", unsatisfiedInstance());

        notifier.onCaseLifecycle(caseEvent("CaseCompensating", "t", "T"));
    }

    @Test
    void noOp_whenDataSourceNotResolved() {
        var registry = mock(DataSourceRegistry.class);
        when(registry.resolveSource(any(Path.class), any(String.class)))
                .thenReturn(Optional.empty());
        var notifier = new CaseCompensationNotifier();
        setField(notifier, "dataSourceRegistryInstance", satisfiedInstance(registry));

        notifier.onCaseLifecycle(caseEvent("CaseCompensating", "t", "T"));
    }

    @Test
    void catchesExceptions_fromDataSource() {
        var registry = mock(DataSourceRegistry.class);
        when(registry.resolveSource(any(Path.class), any(String.class)))
                .thenThrow(new RuntimeException("DataSource unavailable"));
        var notifier = new CaseCompensationNotifier();
        setField(notifier, "dataSourceRegistryInstance", satisfiedInstance(registry));

        notifier.onCaseLifecycle(caseEvent("CaseCompensating", "t", "T"));
    }

    @Test
    void threadsCaseIdAndActorId() {
        var captured = new ArrayList<Object>();
        var notifier = buildNotifier(captured);
        var caseId = UUID.randomUUID();

        notifier.onCaseLifecycle(new CaseLifecycleEvent(
                caseId, "tenant-1", "CompensateCase", "CaseCompensating",
                "COMPENSATING", "admin-user", "System", null,
                "IncidentResponse", null, null, null, null));

        assertThat(captured).hasSize(1);
        var fired = (CaseCompensationEvent) captured.get(0);
        assertThat(fired.caseId()).isEqualTo(caseId);
        assertThat(fired.actorId()).isEqualTo("admin-user");
        assertThat(fired.caseStatus()).isEqualTo("COMPENSATING");
    }

    private CaseCompensationNotifier buildNotifier(ArrayList<Object> captured) {
        @SuppressWarnings("unchecked")
        DataSource<Object> ds = mock(DataSource.class);
        doAnswer(inv -> { captured.add(inv.getArgument(0)); return null; })
                .when(ds).add(any());
        var registry = mock(DataSourceRegistry.class);
        when(registry.resolveSource(eq(NOTIFICATION_DATASOURCE_PATH), eq(PLATFORM_TENANT_ID)))
                .thenReturn(Optional.of(ds));
        var notifier = new CaseCompensationNotifier();
        setField(notifier, "dataSourceRegistryInstance", satisfiedInstance(registry));
        return notifier;
    }

    private CaseLifecycleEvent caseEvent(String eventType, String tenancyId, String defName) {
        return new CaseLifecycleEvent(
                UUID.randomUUID(), tenancyId, "command", eventType,
                "COMPENSATING", "operator-1", "System", null,
                defName, null, null, null, null);
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
