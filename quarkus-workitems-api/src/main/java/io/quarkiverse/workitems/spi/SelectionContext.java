package io.quarkiverse.workitems.spi;

/**
 * Minimal WorkItem context passed to {@link WorkerSelectionStrategy#select}.
 *
 * <p>
 * Decouples strategies from the WorkItem JPA entity — strategies and CaseHub
 * components depend only on this pure record. CaseHub constructs this from
 * {@code TaskRequest}; WorkItems constructs it from the {@code WorkItem} entity.
 *
 * @param category WorkItem category (may be null)
 * @param priority WorkItemPriority name e.g. "HIGH" (may be null)
 * @param requiredCapabilities comma-separated capability tags (may be null)
 * @param candidateGroups comma-separated group names (may be null)
 * @param candidateUsers comma-separated user IDs (may be null)
 */
public record SelectionContext(
        String category,
        String priority,
        String requiredCapabilities,
        String candidateGroups,
        String candidateUsers) {
}
