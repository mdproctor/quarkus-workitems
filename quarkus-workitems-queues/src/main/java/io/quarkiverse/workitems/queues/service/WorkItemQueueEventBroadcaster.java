package io.quarkiverse.workitems.queues.service;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkiverse.workitems.queues.event.WorkItemQueueEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;

/**
 * Application-scoped broadcaster that bridges CDI {@link WorkItemQueueEvent} to
 * a reactive hot stream for Server-Sent Events consumers.
 *
 * <p>
 * Mirrors the pattern of {@code WorkItemEventBroadcaster} in the core module,
 * but for queue-level events (ADDED, REMOVED, CHANGED).
 *
 * <h2>Hot stream semantics</h2>
 * <p>
 * Only events that occur after a client connects are delivered — past events are
 * not replayed. Use {@code GET /queues/{id}} to fetch the current queue contents.
 */
@ApplicationScoped
public class WorkItemQueueEventBroadcaster {

    private final BroadcastProcessor<WorkItemQueueEvent> processor = BroadcastProcessor.create();

    /**
     * CDI observer: re-publishes every {@link WorkItemQueueEvent} to all SSE clients.
     *
     * @param event the queue event fired by {@link FilterEvaluationObserver}
     */
    public void onEvent(@Observes final WorkItemQueueEvent event) {
        processor.onNext(event);
    }

    /**
     * Returns a hot {@link Multi} of queue events, optionally filtered to a single queue.
     *
     * @param queueViewId if non-null, only events for this queue are emitted
     * @return hot stream of matching {@link WorkItemQueueEvent} instances
     */
    public Multi<WorkItemQueueEvent> stream(final UUID queueViewId) {
        Multi<WorkItemQueueEvent> source = processor.toHotStream();
        if (queueViewId != null) {
            source = source.filter(e -> queueViewId.equals(e.queueViewId()));
        }
        return source;
    }
}
