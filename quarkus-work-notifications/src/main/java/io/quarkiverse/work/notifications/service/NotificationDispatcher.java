package io.quarkiverse.work.notifications.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.work.api.NotificationChannel;
import io.quarkiverse.work.api.NotificationPayload;
import io.quarkiverse.work.notifications.model.WorkItemNotificationRule;
import io.quarkiverse.work.runtime.event.WorkItemLifecycleEvent;
import io.quarkiverse.work.runtime.model.WorkItem;

/**
 * CDI observer that dispatches outbound notifications on WorkItem lifecycle events.
 *
 * <h2>Matching</h2>
 * <p>
 * On each {@link WorkItemLifecycleEvent}, loads all enabled rules matching the
 * event type. For each matching rule, looks up the {@link NotificationChannel}
 * with matching {@link NotificationChannel#channelType()} and calls
 * {@link NotificationChannel#send(NotificationPayload)}.
 *
 * <h2>Async delivery</h2>
 * <p>
 * Channel {@code send()} calls run on a virtual-thread executor — they do not
 * block the WorkItem lifecycle transaction. A slow or failing webhook will not
 * affect the WorkItem's committed state.
 *
 * <h2>Zero impact when absent</h2>
 * <p>
 * This bean only exists when {@code quarkus-work-notifications} is on the classpath.
 * The core extension has no knowledge of it.
 */
@ApplicationScoped
public class NotificationDispatcher {

    private static final Logger LOG = Logger.getLogger(NotificationDispatcher.class.getName());
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    @Inject
    Instance<NotificationChannel> channels;

    /**
     * Observe WorkItem lifecycle events and dispatch matching notification rules.
     *
     * <p>
     * Fires {@link TransactionPhase#AFTER_SUCCESS} — only dispatches notifications
     * when the WorkItem mutation has committed successfully. A rolled-back transition
     * produces no notification.
     */
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    void onWorkItemEvent(@Observes(during = TransactionPhase.AFTER_SUCCESS) final WorkItemLifecycleEvent event) {
        final WorkItem wi = (WorkItem) event.source();
        final String eventTypeName = event.eventType().name();
        final String category = wi.category;

        // Load rules in a new transaction (AFTER_SUCCESS means we're outside the original tx)
        final List<WorkItemNotificationRule> candidates;
        try {
            candidates = WorkItemNotificationRule.findEnabledForEventType(eventTypeName);
        } catch (final Exception e) {
            LOG.warning("Failed to load notification rules for " + eventTypeName + ": " + e.getMessage());
            return;
        }

        final List<WorkItemNotificationRule> matched = filterMatching(candidates, eventTypeName, category);
        if (matched.isEmpty()) {
            return;
        }

        final Map<String, NotificationChannel> channelMap = buildChannelMap();

        for (final WorkItemNotificationRule rule : matched) {
            final NotificationChannel channel = channelMap.get(rule.channelType);
            if (channel == null) {
                LOG.warning("No NotificationChannel found for type '" + rule.channelType
                        + "' (rule " + rule.id + ")");
                continue;
            }

            final NotificationPayload payload = new NotificationPayload(
                    event, rule.id, rule.channelType, rule.targetUrl,
                    rule.secret, rule.category);

            CompletableFuture.runAsync(() -> {
                try {
                    channel.send(payload);
                } catch (final Exception ex) {
                    LOG.warning("NotificationChannel " + rule.channelType
                            + " threw on rule " + rule.id + ": " + ex.getMessage());
                }
            }, EXECUTOR);
        }
    }

    // ── package-private statics for unit testing ──────────────────────────────

    /**
     * Returns {@code true} if the rule matches the given event type and category.
     * Exposed for unit testing without CDI or database.
     */
    static boolean matches(final WorkItemNotificationRule rule,
            final String eventType, final String category) {
        if (!rule.enabled) {
            return false;
        }
        if (!rule.parsedEventTypes().contains(eventType)) {
            return false;
        }
        // null rule category = wildcard; otherwise must match exactly
        if (rule.category != null && !rule.category.equals(category)) {
            return false;
        }
        return true;
    }

    /**
     * Filter a list of rules to those matching the given event type and category.
     * Exposed for unit testing without CDI or database.
     */
    static List<WorkItemNotificationRule> filterMatching(
            final List<WorkItemNotificationRule> rules,
            final String eventType,
            final String category) {
        return rules.stream()
                .filter(r -> matches(r, eventType, category))
                .toList();
    }

    private Map<String, NotificationChannel> buildChannelMap() {
        return channels.stream()
                .collect(Collectors.toMap(
                        NotificationChannel::channelType,
                        Function.identity(),
                        (a, b) -> a)); // first wins on duplicate type
    }
}
