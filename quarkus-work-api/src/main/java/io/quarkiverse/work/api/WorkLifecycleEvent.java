package io.quarkiverse.work.api;

import java.util.Map;

/**
 * Abstract base for CDI lifecycle events fired when a work unit transitions state.
 *
 * <p>
 * Observers declared as {@code @Observes WorkLifecycleEvent} receive any subtype,
 * including {@code WorkItemLifecycleEvent} from quarkus-workitems and future event
 * types from CaseHub.
 *
 * <p>
 * Implementations carry the underlying work unit so observers can act on it without
 * an additional store lookup. The {@link #context()} map is pre-built by the work-unit
 * domain and used directly by the filter engine for JEXL condition evaluation.
 */
public abstract class WorkLifecycleEvent {

    /**
     * The canonical event type — what transition just occurred.
     */
    public abstract WorkEventType eventType();

    /**
     * A flat {@code Map<String, Object>} representing the work unit's current field values.
     * Used by the filter engine for JEXL condition evaluation.
     * Values must be directly accessible to JEXL (no JPMS-restricted reflection).
     */
    public abstract Map<String, Object> context();

    /**
     * The underlying work unit (e.g. {@code WorkItem} in quarkus-workitems).
     * {@code FilterAction} implementations downcast to the concrete type they expect.
     * Never null.
     */
    public abstract Object source();
}
