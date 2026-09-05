package io.casehub.work.runtime.event;

import io.casehub.platform.api.datasource.DataSource;
import io.casehub.platform.api.datasource.DataSourceRegistry;
import io.casehub.platform.api.subscription.EventFieldDescriptor;
import io.casehub.platform.api.subscription.EventTypeDescriptor;
import io.casehub.platform.api.subscription.EventTypeRegistry;
import io.casehub.work.api.WorkCloudEventTypes;
import io.casehub.work.api.WorkEventType;
import io.casehub.work.api.WorkItemLifecycleEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.casehub.platform.api.identity.TenancyConstants.PLATFORM_TENANT_ID;
import static io.casehub.platform.api.subscription.SubscriptionConstants.NOTIFICATION_DATASOURCE_PATH;

@ApplicationScoped
public class WorkItemSubscriptionBridge {

    private static final Logger LOG = Logger.getLogger(WorkItemSubscriptionBridge.class);

    private static final Set<WorkEventType> EMITTED_EVENT_TYPES = Set.of(
            WorkEventType.CREATED, WorkEventType.ASSIGNED, WorkEventType.STARTED,
            WorkEventType.COMPLETED, WorkEventType.REJECTED, WorkEventType.FAULTED,
            WorkEventType.DELEGATED, WorkEventType.DELEGATION_ACCEPTED,
            WorkEventType.DELEGATION_DECLINED, WorkEventType.RELEASED,
            WorkEventType.SUSPENDED, WorkEventType.RESUMED, WorkEventType.CANCELLED,
            WorkEventType.OBSOLETE, WorkEventType.EXPIRED, WorkEventType.CLAIM_EXPIRED,
            WorkEventType.SPAWNED, WorkEventType.ESCALATED,
            WorkEventType.DEADLINE_EXTENDED, WorkEventType.SLA_REASSIGNED,
            WorkEventType.SLA_EXTENDED, WorkEventType.SIGNAL_RECEIVED,
            WorkEventType.MANUALLY_ESCALATED, WorkEventType.PROGRESS_UPDATE,
            WorkEventType.COMPENSATION_STARTED, WorkEventType.COMPENSATION_COMPLETED
    );

    @Inject
    Instance<DataSourceRegistry> dataSourceRegistryInstance;

    @Inject
    Instance<EventTypeRegistry> eventTypeRegistryInstance;

    void onStartup(@Observes final StartupEvent event) {
        if (eventTypeRegistryInstance.isUnsatisfied()) {
            return;
        }
        final EventTypeRegistry registry = eventTypeRegistryInstance.get();
        for (final WorkEventType wet : EMITTED_EVENT_TYPES) {
            final String eventType = WorkCloudEventTypes.PREFIX
                    + wet.name().toLowerCase(Locale.ROOT);
            registry.register(new EventTypeDescriptor(
                    eventType,
                    "WorkItem " + wet.name().toLowerCase(Locale.ROOT),
                    null,
                    workItemEventFields()));
        }
        LOG.infof("Registered %d work event types with subscription engine",
                EMITTED_EVENT_TYPES.size());
    }

    void onWorkItemEvent(
            @Observes(during = TransactionPhase.AFTER_SUCCESS)
            final WorkItemLifecycleEvent event) {
        if (dataSourceRegistryInstance.isUnsatisfied()) {
            return;
        }
        try {
            dataSourceRegistryInstance.get()
                                      .resolveSource(NOTIFICATION_DATASOURCE_PATH, PLATFORM_TENANT_ID)
                                      .ifPresent(ds -> {
                                          @SuppressWarnings("unchecked")
                                          var rawDs = (DataSource<Object>) ds;
                                          rawDs.add(event);
                                      });
        } catch (final Exception e) {
            LOG.warnf("Subscription bridge failed for %s: %s",
                      event.type(), e.getMessage());
        }
    }

    private static List<EventFieldDescriptor> workItemEventFields() {
        return List.of(
                new EventFieldDescriptor("status", "Status", "string"),
                new EventFieldDescriptor("assigneeId", "Assignee", "string"),
                new EventFieldDescriptor("candidateGroups", "Candidate Groups", "string"),
                new EventFieldDescriptor("outcome", "Outcome", "string"),
                new EventFieldDescriptor("types", "Types", "string")
        );
    }
}
