package io.quarkiverse.tarkus.flow;

import java.util.concurrent.CompletableFuture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkiverse.tarkus.runtime.model.WorkItem;
import io.quarkiverse.tarkus.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.tarkus.runtime.model.WorkItemPriority;
import io.quarkiverse.tarkus.runtime.service.WorkItemService;

/**
 * CDI bridge for integrating Tarkus human task inbox with Quarkus Flow workflows.
 *
 * <p>
 * Workflow developers inject this bean and call {@link #requestApproval} from a
 * flow task function. The method creates a WorkItem and returns a CompletableFuture
 * that completes when a human resolves the WorkItem via the Tarkus REST API.
 *
 * <p>
 * Example usage in a Flow definition:
 *
 * <pre>{@code
 * &#64;Inject HumanTaskFlowBridge humanTask;
 *
 * tasks.function("get-approval", ctx ->
 *     humanTask.requestApproval("Review document", "alice", WorkItemPriority.HIGH, null)
 * )
 * }</pre>
 */
@ApplicationScoped
public class HumanTaskFlowBridge {

    @Inject
    WorkItemService workItemService;

    @Inject
    PendingWorkItemRegistry registry;

    /**
     * Request human approval by creating a Tarkus WorkItem and suspending
     * until the item is completed or rejected.
     *
     * @param title human-readable task name (required)
     * @param description what the human needs to do (nullable)
     * @param assigneeId who should act on it (nullable — use candidateGroups instead if routing)
     * @param priority task priority (null defaults to NORMAL)
     * @param payload JSON context for the human to act on (nullable)
     * @return a future that completes with the resolution JSON when the human acts,
     *         or completes exceptionally if the WorkItem is rejected or cancelled
     */
    public CompletableFuture<String> requestApproval(
            final String title,
            final String description,
            final String assigneeId,
            final WorkItemPriority priority,
            final String payload) {

        final WorkItemCreateRequest request = new WorkItemCreateRequest(
                title, description, null, null,
                priority != null ? priority : WorkItemPriority.NORMAL,
                assigneeId, null, null, null,
                "tarkus-flow",
                payload, null, null, null);

        final WorkItem workItem = workItemService.create(request);
        return registry.register(workItem.id);
    }

    /**
     * Request human approval with candidate groups for work-queue routing.
     *
     * @param title human-readable task name
     * @param description what the human needs to do (nullable)
     * @param candidateGroups comma-separated group names (e.g. "finance-team,managers")
     * @param priority task priority (null defaults to NORMAL)
     * @param payload JSON context for the human (nullable)
     * @return a future that completes with the resolution JSON when a group member acts
     */
    public CompletableFuture<String> requestGroupApproval(
            final String title,
            final String description,
            final String candidateGroups,
            final WorkItemPriority priority,
            final String payload) {

        final WorkItemCreateRequest request = new WorkItemCreateRequest(
                title, description, null, null,
                priority != null ? priority : WorkItemPriority.NORMAL,
                null, candidateGroups, null, null,
                "tarkus-flow",
                payload, null, null, null);

        final WorkItem workItem = workItemService.create(request);
        return registry.register(workItem.id);
    }
}
