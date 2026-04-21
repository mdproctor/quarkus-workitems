package io.quarkiverse.workitems.spi;

import java.util.Set;

/**
 * A potential assignee for a WorkItem.
 *
 * <p>
 * The {@code activeWorkItemCount} is pre-populated by
 * {@code WorkItemAssignmentService} before the candidate list is passed to
 * {@link WorkerSelectionStrategy#select}. A count of 0 means either zero
 * active items or that the registry did not provide workload data.
 *
 * <p>
 * CaseHub alignment: corresponds to {@code HumanWorkerProfile}.
 */
public record WorkerCandidate(String id, Set<String> capabilities, int activeWorkItemCount) {

    /** Convenience factory — capabilities empty, workload 0. */
    public static WorkerCandidate of(final String id) {
        return new WorkerCandidate(id, Set.of(), 0);
    }

    /** Returns a new candidate with the updated active WorkItem count. */
    public WorkerCandidate withActiveWorkItemCount(final int count) {
        return new WorkerCandidate(id, capabilities, count);
    }
}
