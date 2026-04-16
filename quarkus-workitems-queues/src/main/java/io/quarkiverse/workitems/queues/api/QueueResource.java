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

import io.quarkiverse.workitems.queues.model.FilterScope;
import io.quarkiverse.workitems.queues.model.QueueView;
import io.quarkiverse.workitems.runtime.api.WorkItemMapper;
import io.quarkiverse.workitems.runtime.api.WorkItemResponse;
import io.quarkiverse.workitems.runtime.repository.WorkItemRepository;

/** REST resource for managing queue views and querying their live content. */
@Path("/queues")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QueueResource {

    @Inject
    WorkItemRepository workItemRepo;

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
     * Query the live content of a queue view — returns all WorkItems matching its label pattern.
     *
     * @param id the queue view UUID
     * @return 200 with the list of matching WorkItems, or 404 if the queue view is not found
     */
    @GET
    @Path("/{id}")
    @Transactional
    public Response query(@PathParam("id") final UUID id) {
        final QueueView q = QueueView.findById(id);
        if (q == null) {
            return Response.status(404).entity(Map.of("error", "Queue view not found")).build();
        }
        final List<WorkItemResponse> items = workItemRepo.findByLabelPattern(q.labelPattern)
                .stream().map(WorkItemMapper::toResponse).toList();
        return Response.ok(items).build();
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
}
