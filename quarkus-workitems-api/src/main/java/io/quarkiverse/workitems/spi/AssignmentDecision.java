package io.quarkiverse.workitems.spi;

/**
 * Immutable outcome of a {@link WorkerSelectionStrategy#select} call.
 *
 * <p>
 * A {@code null} field means "no change to this WorkItem field". Only non-null
 * fields are applied to the WorkItem by {@code WorkItemAssignmentService}.
 *
 * @param assigneeId pre-assign to this actor; null = leave unassigned (pool open)
 * @param candidateGroups replace candidateGroups; null = keep as-is
 * @param candidateUsers replace candidateUsers; null = keep as-is
 */
public record AssignmentDecision(String assigneeId, String candidateGroups, String candidateUsers) {

    /** No changes — pool open, claim-first behaviour. */
    public static AssignmentDecision noChange() {
        return new AssignmentDecision(null, null, null);
    }

    /** Pre-assign to a specific actor; candidate fields unchanged. */
    public static AssignmentDecision assignTo(final String id) {
        return new AssignmentDecision(id, null, null);
    }

    /** Narrow the candidate pool without pre-assigning. */
    public static AssignmentDecision narrowCandidates(final String groups, final String users) {
        return new AssignmentDecision(null, groups, users);
    }

    /** True when this decision makes no changes to the WorkItem. */
    public boolean isNoOp() {
        return assigneeId == null && candidateGroups == null && candidateUsers == null;
    }
}
