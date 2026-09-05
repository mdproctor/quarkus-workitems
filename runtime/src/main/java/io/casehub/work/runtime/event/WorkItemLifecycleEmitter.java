package io.casehub.work.runtime.event;

import io.casehub.work.api.WorkItemLifecycleEvent;
import io.casehub.work.api.WorkItemStatusEvent;
import io.casehub.work.api.spi.WorkItemObserver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

/**
 * Fires WorkItemLifecycleEvent on both sync and async CDI channels,
 * then dispatches to SPI {@link WorkItemObserver} instances.
 *
 * The same event instance is passed to both fire() and fireAsync().
 * WorkItemLifecycleEvent is immutable (all final fields) — this is safe.
 * Async observer failures are logged but do not propagate.
 */
@ApplicationScoped
public class WorkItemLifecycleEmitter {

    private static final Logger LOG = Logger.getLogger(WorkItemLifecycleEmitter.class);

    @Inject
    Event<WorkItemLifecycleEvent> delegate;

    @Inject
    Instance<WorkItemObserver> observers;

    public void emit(final WorkItemLifecycleEvent event) {
        delegate.fire(event);
        delegate.fireAsync(event)
                .exceptionally(ex -> {
                    LOG.warnf(ex, "Async lifecycle observer failure for %s", event.type());
                    return null;
                });

        if (!observers.isUnsatisfied()) {
            final WorkItemStatusEvent statusEvent = toStatusEvent(event);
            for (final WorkItemObserver observer : observers) {
                try {
                    observer.onStatusChange(statusEvent);
                } catch (final Exception ex) {
                    LOG.warnf(ex, "WorkItemObserver failure for %s", event.type());
                }
            }
        }
    }

    private static WorkItemStatusEvent toStatusEvent(final WorkItemLifecycleEvent event) {
        return new WorkItemStatusEvent(
                event.eventType(), event.workItemId(), event.status(),
                event.actor(), event.detail(), event.callerRef(),
                event.assigneeId(), event.candidateGroups(), event.outcome(),
                event.tenancyId(), event.occurredAt(),
                event.workItem() != null ? event.workItem().originRef() : null);
    }
}
