package io.quarkiverse.workitems.queues.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkiverse.workitems.queues.model.WorkItemQueueState;
import io.quarkiverse.workitems.runtime.api.WorkItemMapper;
import io.quarkiverse.workitems.runtime.event.WorkItemLifecycleEvent;
import io.quarkiverse.workitems.runtime.model.WorkItemStatus;
import io.quarkiverse.workitems.runtime.repository.WorkItemStore;
import io.quarkiverse.workitems.runtime.service.WorkItemService;

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
    WorkItemStore workItemStore;

    @Inject
    WorkItemService workItemService;

    @Inject
    Event<WorkItemLifecycleEvent> lifecycleEvent;

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
    /**
     * Queue pickup — claims a WorkItem from a queue.
     *
     * <p>
     * Two cases are handled:
     * <ul>
     * <li><b>PENDING</b> — standard claim; delegates to {@link WorkItemService#claim}.</li>
     * <li><b>ASSIGNED + relinquishable</b> — soft pickup; the current assignee has signalled
     * willingness to release. Ownership transfers to {@code claimant} and the
     * relinquishable flag is cleared automatically.</li>
     * </ul>
     *
     * <p>
     * Returns {@code 409 Conflict} if the WorkItem is {@code ASSIGNED} but <em>not</em>
     * marked as relinquishable — use the standard {@code PUT /workitems/{id}/claim} path
     * after the current assignee explicitly releases or delegates.
     *
     * @param id the WorkItem UUID
     * @param claimant the user taking ownership
     * @return 200 with the updated WorkItemResponse, 404 if not found, 409 if not claimable
     */
    @PUT
    @Path("/{id}/pickup")
    @Transactional
    public Response pickup(@PathParam("id") final UUID id,
            @QueryParam("claimant") final String claimant) {
        final var wi = workItemStore.get(id)
                .orElse(null);
        if (wi == null) {
            return Response.status(404).entity(Map.of("error", "WorkItem not found: " + id)).build();
        }

        if (wi.status == WorkItemStatus.PENDING) {
            // Standard claim path
            final var claimed = workItemService.claim(id, claimant);
            return Response.ok(WorkItemMapper.toResponse(claimed)).build();
        }

        if (wi.status == WorkItemStatus.ASSIGNED) {
            // Soft pickup — only allowed when assignee has flagged relinquishable
            final var state = WorkItemQueueState.<WorkItemQueueState> findByIdOptional(id).orElse(null);
            if (state == null || !state.relinquishable) {
                return Response.status(409)
                        .entity(Map.of("error",
                                "WorkItem is ASSIGNED but not marked as relinquishable. "
                                        + "Current assignee must call PUT /workitems/{id}/relinquishable first."))
                        .build();
            }
            // Transfer ownership
            wi.assigneeId = claimant;
            wi.assignedAt = Instant.now();
            final var saved = workItemStore.put(wi);
            // Clear the flag — the signal is consumed on first pickup
            state.relinquishable = false;
            // Fire lifecycle event so ledger, filter engine, and dashboard observers react
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("ASSIGNED", saved, claimant,
                    "Queue pickup (relinquishable takeover)"));
            return Response.ok(WorkItemMapper.toResponse(saved)).build();
        }

        return Response.status(409)
                .entity(Map.of("error", "Cannot pick up WorkItem in status: " + wi.status))
                .build();
    }

    @PUT
    @Path("/{id}/relinquishable")
    @Transactional
    public Response setRelinquishable(@PathParam("id") final UUID id,
            final RelinquishableRequest req) {
        if (workItemStore.get(id).isEmpty()) {
            return Response.status(404).entity(Map.of("error", "WorkItem not found: " + id)).build();
        }
        final WorkItemQueueState state = WorkItemQueueState.findOrCreate(id);
        state.relinquishable = req.relinquishable();
        return Response.ok(Map.of("workItemId", id, "relinquishable", state.relinquishable)).build();
    }
}
