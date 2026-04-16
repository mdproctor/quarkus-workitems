package io.quarkiverse.workitems.queues.api;

import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkiverse.workitems.queues.model.WorkItemQueueState;
import io.quarkiverse.workitems.runtime.repository.WorkItemRepository;

/**
 * REST resource for managing WorkItem soft-assignment (queue state) flags.
 *
 * <p>
 * Shares the {@code /workitems} base path with {@code WorkItemResource}; the
 * {@code /{id}/relinquishable} sub-path is unique and does not conflict.
 */
@Path("/workitems")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QueueStateResource {

    @Inject
    WorkItemRepository workItemRepo;

    /**
     * Request body for setting the relinquishable flag.
     *
     * @param relinquishable {@code true} to mark the WorkItem as willing to be released;
     *        {@code false} to clear the flag
     */
    public record RelinquishableRequest(boolean relinquishable) {
    }

    /**
     * Set or clear the relinquishable flag on a WorkItem.
     *
     * <p>
     * When {@code relinquishable} is {@code true} the assignee is indicating they are
     * willing to release the item back to the queue.
     *
     * @param id the WorkItem UUID
     * @param req the new flag value
     * @return 200 with workItemId and relinquishable, or 404 if the WorkItem is not found
     */
    @PUT
    @Path("/{id}/relinquishable")
    @Transactional
    public Response setRelinquishable(@PathParam("id") final UUID id,
            final RelinquishableRequest req) {
        if (workItemRepo.findById(id).isEmpty()) {
            return Response.status(404).entity(Map.of("error", "WorkItem not found: " + id)).build();
        }
        final WorkItemQueueState state = WorkItemQueueState.findOrCreate(id);
        state.relinquishable = req.relinquishable();
        return Response.ok(Map.of("workItemId", id, "relinquishable", state.relinquishable)).build();
    }
}
