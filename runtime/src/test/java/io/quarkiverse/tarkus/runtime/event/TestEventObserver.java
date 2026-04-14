package io.quarkiverse.tarkus.runtime.event;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * CDI bean in test scope that collects all emitted {@link WorkItemLifecycleEvent}s.
 * Inject this in {@code @QuarkusTest} classes to assert event emission.
 *
 * <p>
 * Refs #25
 */
@ApplicationScoped
public class TestEventObserver {

    private final List<WorkItemLifecycleEvent> events = new ArrayList<>();

    void onEvent(@Observes WorkItemLifecycleEvent event) {
        events.add(event);
    }

    /** Returns an unmodifiable snapshot of all collected events. */
    public List<WorkItemLifecycleEvent> getEvents() {
        return List.copyOf(events);
    }

    /** Clears collected events — call in {@code @BeforeEach} to isolate tests. */
    public void clear() {
        events.clear();
    }

    /** Returns events whose type ends with {@code typeSuffix} (e.g. "created", "assigned"). */
    public List<WorkItemLifecycleEvent> ofType(String typeSuffix) {
        return events.stream()
                .filter(e -> e.type().endsWith(typeSuffix))
                .toList();
    }
}
