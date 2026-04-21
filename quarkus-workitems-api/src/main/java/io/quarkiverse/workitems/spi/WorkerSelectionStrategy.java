package io.quarkiverse.workitems.spi;

import java.util.List;
import java.util.Set;

/**
 * Pluggable worker selection SPI.
 *
 * <p>
 * Implement as {@code @ApplicationScoped @Alternative @Priority(1)} to override
 * the built-in strategy configured by {@code quarkus.workitems.routing.strategy}.
 *
 * <p>
 * Built-in implementations (in the runtime module):
 * <ul>
 * <li>{@code LeastLoadedStrategy} — pre-assigns to fewest-active-items candidate (default)
 * <li>{@code ClaimFirstStrategy} — no pre-assignment; whoever claims first wins
 * </ul>
 *
 * <p>
 * CaseHub alignment: corresponds to {@code WorkerSelectionStrategy} in casehub-engine.
 */
public interface WorkerSelectionStrategy {

    /**
     * Select an assignment outcome from the resolved candidate list.
     *
     * @param context minimal WorkItem context (category, priority, capabilities, pools)
     * @param candidates resolved candidates with pre-populated {@code activeWorkItemCount}
     * @return assignment decision; never null — use {@link AssignmentDecision#noChange()}
     *         to leave the WorkItem unassigned
     */
    AssignmentDecision select(SelectionContext context, List<WorkerCandidate> candidates);

    /**
     * The lifecycle events that trigger this strategy.
     * Override to restrict to a subset. Default: all three events.
     */
    default Set<AssignmentTrigger> triggers() {
        return Set.of(AssignmentTrigger.values());
    }
}
