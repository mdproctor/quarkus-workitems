package io.quarkiverse.workitems.runtime.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkiverse.workitems.runtime.service.WorkItemService;

/**
 * Bulk operations on multiple WorkItems in a single request.
 *
 * <pre>
 * POST /workitems/bulk
 * {
 *   "operation": "claim" | "cancel",
 *   "workItemIds": ["uuid1", "uuid2", ...],
 *   "actorId": "alice",
 *   "reason": "optional — used for cancel"
 * }
 * </pre>
 *
 * <p>
 * Returns per-item results. Partial success is allowed — one failure does not
 * block others. Each item is processed independently.
 *
 * <p>
 * Maximum batch size: 100 items.
 */
@Path("/workitems/bulk")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkItemBulkResource {

    static final int MAX_BATCH_SIZE = 100;

    @Inject
    WorkItemService workItemService;

    /**
     * Bulk operation request.
     *
     * @param operation the operation to apply: {@code "claim"} or {@code "cancel"}
     * @param workItemIds the WorkItem UUIDs to act on; max 100
     * @param actorId the actor performing the operation
     * @param reason optional reason (used for cancel)
     */
    public record BulkRequest(
            String operation,
            List<String> workItemIds,
            String actorId,
            String reason) {
    }

    /** Per-item result in the bulk response. */
    public record BulkItemResult(String id, String status, String error) {

        static BulkItemResult ok(final String id) {
            return new BulkItemResult(id, "ok", null);
        }

        static BulkItemResult error(final String id, final String message) {
            return new BulkItemResult(id, "error", message);
        }
    }

    /**
     * Execute a bulk operation across multiple WorkItems.
     *
     * <p>
     * Each item is processed independently — failures are captured in the result
     * and do not prevent other items from being processed. A 200 response with
     * some {@code "error"} items is a valid partial success.
     *
     * @param request the bulk operation request
     * @return 200 OK with per-item results, 400 on request validation failure
     */
    @POST
    @Transactional
    public Response bulk(final BulkRequest request) {
        if (request == null || request.operation() == null || request.operation().isBlank()) {
            return bad("operation is required");
        }
        if (request.workItemIds() == null || request.workItemIds().isEmpty()) {
            return bad("workItemIds must not be empty");
        }
        if (request.workItemIds().size() > MAX_BATCH_SIZE) {
            return bad("batch size exceeds maximum of " + MAX_BATCH_SIZE);
        }

        final String op = request.operation().toLowerCase();
        if (!List.of("claim", "cancel").contains(op)) {
            return bad("unknown operation '" + request.operation() + "'; supported: claim, cancel");
        }

        final String actor = request.actorId() != null ? request.actorId() : "unknown";
        final List<BulkItemResult> results = new ArrayList<>();

        for (final String idStr : request.workItemIds()) {
            results.add(apply(op, idStr, actor, request.reason()));
        }

        return Response.ok(results).build();
    }

    private BulkItemResult apply(final String op, final String idStr,
            final String actor, final String reason) {
        try {
            final UUID id = UUID.fromString(idStr);
            switch (op) {
                case "claim" -> workItemService.claim(id, actor);
                case "cancel" -> workItemService.cancel(id, actor, reason);
                default -> throw new IllegalArgumentException("Unknown operation: " + op);
            }
            return BulkItemResult.ok(idStr);
        } catch (Exception e) {
            return BulkItemResult.error(idStr, e.getMessage());
        }
    }

    private Response bad(final String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", message)).build();
    }
}
