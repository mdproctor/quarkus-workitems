package io.casehub.work.engine;

import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.platform.api.datasource.DataSource;
import io.casehub.platform.api.datasource.DataSourceRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.Set;

import static io.casehub.platform.api.identity.TenancyConstants.PLATFORM_TENANT_ID;
import static io.casehub.platform.api.subscription.SubscriptionConstants.NOTIFICATION_DATASOURCE_PATH;

@ApplicationScoped
public class CaseCompensationNotifier {

    private static final Logger LOG = Logger.getLogger(CaseCompensationNotifier.class);

    private static final Set<String> COMPENSATION_EVENT_TYPES = Set.of(
            "CaseCompensating", "CaseCompensated", "CaseCompensationFaulted");

    @Inject
    Instance<DataSourceRegistry> dataSourceRegistryInstance;

    void onCaseLifecycle(@ObservesAsync CaseLifecycleEvent event) {
        if (!COMPENSATION_EVENT_TYPES.contains(event.eventType())) {
            return;
        }
        if (dataSourceRegistryInstance.isUnsatisfied()) {
            return;
        }
        CaseCompensationEvent.Kind kind = switch (event.eventType()) {
            case "CaseCompensating" -> CaseCompensationEvent.Kind.STARTED;
            case "CaseCompensated" -> CaseCompensationEvent.Kind.COMPLETED;
            case "CaseCompensationFaulted" -> CaseCompensationEvent.Kind.FAULTED;
            default -> null;
        };
        if (kind == null) {
            return;
        }
        fire(new CaseCompensationEvent(
                kind, event.tenancyId(), event.caseId(),
                event.caseDefinitionName(), event.caseStatus(),
                event.actorId()));
    }

    private void fire(CaseCompensationEvent event) {
        try {
            Optional<DataSource<?>> ds = dataSourceRegistryInstance.get()
                    .resolveSource(NOTIFICATION_DATASOURCE_PATH, PLATFORM_TENANT_ID);
            if (ds.isEmpty()) {
                LOG.warnf("Notification DataSource not available — dropping %s event", event.kind());
                return;
            }
            @SuppressWarnings("unchecked")
            DataSource<Object> source = (DataSource<Object>) ds.get();
            source.add(event);
        } catch (Exception e) {
            LOG.warnf("Failed to fire compensation event %s: %s", event.kind(), e.getMessage());
        }
    }
}
