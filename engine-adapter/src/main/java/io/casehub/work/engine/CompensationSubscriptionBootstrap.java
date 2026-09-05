package io.casehub.work.engine;

import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.subscription.EventFieldDescriptor;
import io.casehub.platform.api.subscription.EventTypeDescriptor;
import io.casehub.platform.api.subscription.EventTypeRegistry;
import io.casehub.platform.api.subscription.NotificationTarget;
import io.casehub.platform.api.subscription.NotificationTemplate;
import io.casehub.platform.api.subscription.Subscription;
import io.casehub.platform.api.subscription.SubscriptionInput;
import io.casehub.platform.api.subscription.SubscriptionScope;
import io.casehub.platform.api.subscription.SubscriptionStore;
import io.casehub.platform.api.subscription.TargetType;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.casehub.platform.api.identity.TenancyConstants.PLATFORM_TENANT_ID;

@ApplicationScoped
public class CompensationSubscriptionBootstrap {

    private static final Logger LOG = Logger.getLogger(CompensationSubscriptionBootstrap.class);
    private static final String OWNER_ID = "system:compensation";
    private static final String COMPENSATION_PREFIX = "io.casehub.engine.case.compensation.";
    private static final String WORK_COMPENSATION_PREFIX = "io.casehub.work.workitem.compensation_";

    @Inject
    Instance<SubscriptionStore> subscriptionStoreInstance;

    @Inject
    Instance<EventTypeRegistry> eventTypeRegistryInstance;

    void onStartup(@Observes StartupEvent event) {
        if (subscriptionStoreInstance.isUnsatisfied()) {
            return;
        }
        SubscriptionStore store = subscriptionStoreInstance.get();

        Set<String> existing = store.findAllEnabled()
                .filter(s -> s.eventType().startsWith(COMPENSATION_PREFIX)
                          || s.eventType().startsWith(WORK_COMPENSATION_PREFIX))
                .map(Subscription::eventType)
                .collect(Collectors.toSet());

        registerWorkSubscription(store, existing, "started",
                "assigneeId", "WorkItem compensation started",
                NotificationSeverity.WARNING);
        registerWorkSubscription(store, existing, "completed",
                "assigneeId", "WorkItem compensation completed",
                NotificationSeverity.INFO);

        registerCaseSubscription(store, existing, "started",
                "Case compensation started: {caseDefinitionName}",
                NotificationSeverity.URGENT);
        registerCaseSubscription(store, existing, "completed",
                "Case compensation completed: {caseDefinitionName}",
                NotificationSeverity.INFO);
        registerCaseSubscription(store, existing, "faulted",
                "Case compensation FAULTED: {caseDefinitionName}",
                NotificationSeverity.URGENT);

        if (!eventTypeRegistryInstance.isUnsatisfied()) {
            registerCaseEventTypes(eventTypeRegistryInstance.get());
        }
    }

    private void registerWorkSubscription(SubscriptionStore store, Set<String> existing,
                                          String suffix, String targetField,
                                          String titlePattern, NotificationSeverity severity) {
        String eventType = WORK_COMPENSATION_PREFIX + suffix;
        if (existing.contains(eventType)) {
            return;
        }
        try {
            store.store(new SubscriptionInput(
                    OWNER_ID, PLATFORM_TENANT_ID,
                    "work.compensation." + suffix, eventType,
                    List.of(),
                    List.of(new NotificationTarget(TargetType.EVENT_FIELD, targetField)),
                    false,
                    new NotificationTemplate(titlePattern, null, severity,
                            "work.compensation." + suffix, null,
                            "workitem", "workItemId", "actor"),
                    true, SubscriptionScope.SYSTEM));
            LOG.infof("Registered compensation subscription for %s", eventType);
        } catch (Exception e) {
            LOG.warnf("Failed to register compensation subscription for %s: %s",
                      eventType, e.getMessage());
        }
    }

    private void registerCaseSubscription(SubscriptionStore store, Set<String> existing,
                                          String suffix, String titlePattern,
                                          NotificationSeverity severity) {
        String eventType = COMPENSATION_PREFIX + suffix;
        if (existing.contains(eventType)) {
            return;
        }
        try {
            store.store(new SubscriptionInput(
                    OWNER_ID, PLATFORM_TENANT_ID,
                    "case.compensation." + suffix, eventType,
                    List.of(),
                    List.of(new NotificationTarget(TargetType.EVENT_FIELD, "actorId")),
                    false,
                    new NotificationTemplate(titlePattern, null, severity,
                            "case.compensation." + suffix, null,
                            "case", "caseId", "actorId"),
                    true, SubscriptionScope.SYSTEM));
            LOG.infof("Registered compensation subscription for %s", eventType);
        } catch (Exception e) {
            LOG.warnf("Failed to register compensation subscription for %s: %s",
                      eventType, e.getMessage());
        }
    }

    private void registerCaseEventTypes(EventTypeRegistry registry) {
        List<EventFieldDescriptor> fields = List.of(
                new EventFieldDescriptor("caseId", "Case ID", "string"),
                new EventFieldDescriptor("caseDefinitionName", "Case Definition", "string"),
                new EventFieldDescriptor("caseStatus", "Case Status", "string"),
                new EventFieldDescriptor("actorId", "Actor", "string"));
        for (CaseCompensationEvent.Kind kind : CaseCompensationEvent.Kind.values()) {
            String eventType = "io.casehub.engine.case.compensation." + kind.name().toLowerCase();
            String displayName = "Case compensation " + kind.name().toLowerCase();
            registry.register(new EventTypeDescriptor(eventType, displayName, null, fields));
        }
        LOG.info("Registered 3 case compensation event types with subscription engine");
    }
}
