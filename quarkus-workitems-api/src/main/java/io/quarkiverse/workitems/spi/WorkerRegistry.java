package io.quarkiverse.workitems.spi;

import java.util.List;

/**
 * Resolves a group name to its member {@link WorkerCandidate}s.
 *
 * <p>
 * Implement as {@code @ApplicationScoped @Alternative @Priority(1)} to connect
 * LDAP, Keycloak, CaseHub WorkerRegistry, or any directory service.
 *
 * <p>
 * The default implementation ({@code NoOpWorkerRegistry} in the runtime) returns
 * an empty list for every group — groups remain claim-first until the application
 * registers a real implementation.
 *
 * <p>
 * CaseHub alignment: corresponds to the {@code WorkerRegistry} concept in casehub-engine.
 */
public interface WorkerRegistry {

    /**
     * Resolve {@code groupName} to its member candidates.
     *
     * <p>
     * Implementations may pre-populate {@link WorkerCandidate#capabilities()};
     * {@code activeWorkItemCount} is populated by {@code WorkItemAssignmentService}
     * if left at 0.
     *
     * @param groupName group name as stored in {@code WorkItem.candidateGroups}
     * @return member candidates; empty list if group is unknown or lookup fails
     */
    List<WorkerCandidate> resolveGroup(String groupName);
}
