package io.quarkiverse.workitems.queues.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.reactive.RestStreamElementType;

import io.quarkiverse.workitems.queues.event.WorkItemQueueEvent;
import io.quarkiverse.workitems.queues.model.FilterScope;
import io.quarkiverse.workitems.queues.model.QueueView;
import io.quarkiverse.workitems.queues.service.ExpressionDescriptor;
import io.quarkiverse.workitems.queues.service.FilterEvaluatorRegistry;
import io.quarkiverse.workitems.queues.service.WorkItemQueueEventBroadcaster;
import io.quarkiverse.workitems.runtime.api.WorkItemMapper;
import io.quarkiverse.workitems.runtime.api.WorkItemResponse;
import io.quarkiverse.workitems.runtime.repository.WorkItemQuery;
import io.quarkiverse.workitems.runtime.repository.WorkItemStore;
import io.smallrye.mutiny.Multi;

/** REST resource for managing queue views and querying their live content. */
@Path("/queues")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QueueResource {

    @Inject
    WorkItemStore workItemStore;

    @Inject
    WorkItemQueueEventBroadcaster queueEventBroadcaster;

    @Inject
    FilterEvaluatorRegistry evaluatorRegistry;

    /**
     * Request body for creating a new queue view.
     *
     * @param name display name
     * @param labelPattern label pattern used to populate the queue
     * @param scope visibility scope
     * @param ownerId owner identity for PERSONAL-scoped views
     * @param additionalConditions optional extra filter conditions
     * @param sortField field to sort results by
     * @param sortDirection sort direction (ASC or DESC)
     */
    public record CreateQueueRequest(String name, String labelPattern, FilterScope scope,
            String ownerId, String additionalConditions, String sortField, String sortDirection) {
    }

    /**
     * List all queue views.
     *
     * @return list of all queue views (id, name, labelPattern, scope)
     */
    @GET
    @Transactional
    public List<Map<String, Object>> list() {
        return QueueView.<QueueView> listAll().stream()
                .map(q -> Map.<String, Object> of(
                        "id", q.id, "name", q.name, "labelPattern", q.labelPattern, "scope", q.scope))
                .toList();
    }

    /**
     * Create a new queue view.
     *
     * @param req the queue view definition
     * @return 201 Created with the new queue view id and name, or 400 if labelPattern is missing
     */
    @POST
    @Transactional
    public Response create(final CreateQueueRequest req) {
        if (req.labelPattern() == null || req.labelPattern().isBlank()) {
            return Response.status(400).entity(Map.of("error", "labelPattern is required")).build();
        }
        final QueueView q = new QueueView();
        q.name = req.name();
        q.labelPattern = req.labelPattern();
        q.scope = req.scope() != null ? req.scope() : FilterScope.ORG;
        q.ownerId = req.ownerId();
        q.additionalConditions = req.additionalConditions();
        q.sortField = req.sortField() != null ? req.sortField() : "createdAt";
        q.sortDirection = req.sortDirection() != null ? req.sortDirection() : "ASC";
        q.persist();
        return Response.status(201)
                .entity(Map.of("id", q.id, "name", q.name, "labelPattern", q.labelPattern)).build();
    }

    /**
     * Query the live content of a queue view — returns all WorkItems matching its label pattern,
     * further filtered by {@code additionalConditions} (JEXL expression) if set, and sorted by
     * {@code sortField}/{@code sortDirection}.
     *
     * @param id the queue view UUID
     * @return 200 with the filtered, sorted list of matching WorkItems, or 404 if not found
     */
    @GET
    @Path("/{id}")
    @Transactional
    public Response query(@PathParam("id") final UUID id) {
        final QueueView q = QueueView.findById(id);
        if (q == null) {
            return Response.status(404).entity(Map.of("error", "Queue view not found")).build();
        }
        var candidates = workItemStore.scan(WorkItemQuery.byLabelPattern(q.labelPattern));

        // Apply additionalConditions JEXL expression if set
        if (q.additionalConditions != null && !q.additionalConditions.isBlank()) {
            final var jexl = evaluatorRegistry.find("jexl");
            if (jexl != null) {
                candidates = candidates.stream()
                        .filter(wi -> jexl.evaluate(wi, ExpressionDescriptor.of("jexl", q.additionalConditions)))
                        .toList();
            }
        }

        final var items = candidates.stream()
                .map(WorkItemMapper::toResponse)
                .sorted(buildComparator(q.sortField, q.sortDirection))
                .toList();
        return Response.ok(items).build();
    }

    private java.util.Comparator<WorkItemResponse> buildComparator(
            final String sortField, final String sortDirection) {
        final boolean asc = !"DESC".equalsIgnoreCase(sortDirection);
        final java.util.Comparator<WorkItemResponse> base = switch (sortField != null ? sortField : "createdAt") {
            case "priority" -> java.util.Comparator.comparing(
                    r -> r.priority() != null ? r.priority().name() : "");
            case "title" -> java.util.Comparator.comparing(
                    r -> r.title() != null ? r.title() : "");
            default -> // createdAt
                java.util.Comparator.comparing(
                        r -> r.createdAt() != null ? r.createdAt() : java.time.Instant.EPOCH);
        };
        return asc ? base : base.reversed();
    }

    /**
     * Delete a queue view by id.
     *
     * @param id the queue view UUID
     * @return 204 No Content on success, or 404 if the queue view is not found
     */
    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") final UUID id) {
        final QueueView q = QueueView.findById(id);
        if (q == null) {
            return Response.status(404).entity(Map.of("error", "Not found")).build();
        }
        q.delete();
        return Response.noContent().build();
    }

    /**
     * Server-Sent Events stream of {@link WorkItemQueueEvent} (ADDED/REMOVED/CHANGED)
     * scoped to a specific queue view.
     *
     * <p>
     * Hot stream — only events after the client connects are delivered.
     * Use {@code GET /queues/{id}/workitems} to fetch the current queue contents.
     *
     * @param queueViewId the queue view UUID
     * @return SSE stream of queue membership events for this queue
     */
    @GET
    @Path("/{id}/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<WorkItemQueueEvent> streamQueueEvents(@PathParam("id") final UUID queueViewId) {
        return queueEventBroadcaster.stream(queueViewId);
    }
}
