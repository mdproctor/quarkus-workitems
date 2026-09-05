package io.casehub.work.rest;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.work.api.WorkItem;
import io.casehub.work.api.WorkItemLifecycleEvent;
import io.casehub.work.api.WorkItemPriority;
import io.casehub.work.api.WorkItemQuery;
import io.casehub.work.api.WorkItemRootView;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.api.WorkItemSummary;
import io.casehub.work.api.spi.WorkItemOperations;
import io.casehub.work.api.spi.WorkItemStore;
import io.casehub.work.runtime.event.WorkItemEventBroadcaster;
import io.casehub.work.runtime.model.AuditEntry;
import io.casehub.work.runtime.model.WorkItemLink;
import io.casehub.work.runtime.model.WorkItemNote;
import io.casehub.work.runtime.model.WorkItemRelationType;
import io.casehub.work.runtime.repository.AuditEntryStore;
import io.casehub.work.runtime.repository.WorkItemLinkStore;
import io.casehub.work.runtime.repository.WorkItemNoteStore;
import io.casehub.work.runtime.repository.WorkItemRelationStore;
import io.casehub.work.runtime.service.LabelNotFoundException;
import io.casehub.work.runtime.service.WorkItemNotFoundException;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

@Path("/workitems")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class WorkItemResource {

    @Inject
    WorkItemOperations workItemService;

    @Inject
    AuditEntryStore auditStore;

    @Inject
    WorkItemStore workItemStore;

    @Inject
    WorkItemNoteStore noteStore;

    @Inject
    WorkItemRelationStore relationStore;

    @Inject
    WorkItemLinkStore linkStore;

    @Inject
    WorkItemEventBroadcaster broadcaster;

    @Inject
    CurrentPrincipal currentPrincipal;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(final CreateWorkItemRequest request) {
        try {
            final WorkItem created  = workItemService.create(WorkItemMapper.toServiceRequest(request));
            final URI            location = URI.create("/workitems/" + created.id());
            return Response.created(location).entity(WorkItemMapper.toResponse(created)).build();
        } catch (io.casehub.work.api.MalformedCapabilityException | io.casehub.work.api.UnknownCapabilityException e) {
            // Let the dedicated @Provider mappers handle these — do not swallow them here.
            throw e;
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    public record AddLabelRequest(String path, String appliedBy) {
    }

    public record CompensateRequest(String title, String candidateGroups, String actor, String reason) {}

    @POST
    @Path("/{id}/compensate")
    @Consumes(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
    public Response compensate(@jakarta.ws.rs.PathParam("id") final UUID id,
                               final CompensateRequest request) {
        try {
            final io.casehub.work.api.WorkItem compensating = workItemService.compensate(
                    id,
                    io.casehub.work.api.WorkItemCreateRequest.builder()
                                                             .title(request.title())
                                                             .candidateGroups(request.candidateGroups())
                                                             .createdBy(request.actor())
                                                             .build(),
                    request.actor(),
                    request.reason());
            return Response.status(Response.Status.CREATED)
                           .entity(WorkItemMapper.toResponse(compensating))
                           .build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                           .entity(java.util.Map.of("error", e.getMessage()))
                           .build();
        }
    }


    @GET
    public List<WorkItemResponse> listAll(
            @QueryParam("label") final String label,
            @QueryParam("outcome") final String outcome) {
        Stream<WorkItem> stream;
        if (label != null && !label.isBlank()) {
            stream = workItemStore.scan(WorkItemQuery.byLabelPattern(label)).stream();
        } else {
            final WorkItemQuery.Builder qb = WorkItemQuery.builder();
            if (outcome != null && !outcome.isBlank()) qb.outcome(outcome);
            stream = workItemStore.scan(qb.build()).stream();
        }
        // byLabelPattern bypasses the query builder; post-filter by outcome when both are present
        if (label != null && !label.isBlank() && outcome != null && !outcome.isBlank()) {
            final String outcomeFilter = outcome;
            stream = stream.filter(wi -> outcomeFilter.equals(wi.outcome()));
        }
        return stream.map(WorkItemMapper::toResponse).toList();
    }

    /**
     * Aggregate counts for dashboard widgets — one fast call, no payload loading.
     *
     * <p>
     * Supports the same filter parameters as {@code GET /inbox} (assignee,
     * candidateGroup, type, priority, status). All parameters are optional;
     * omitting them returns counts across all WorkItems.
     */
    @GET
    @Path("/inbox/summary")
    public WorkItemSummary inboxSummary(
            @QueryParam("assignee") final String assignee,
            @QueryParam("candidateGroup") final List<String> candidateGroups,
            @QueryParam("candidateUser") final String candidateUser,
            @QueryParam("status") final WorkItemStatus status,
            @QueryParam("priority") final WorkItemPriority priority,
            @QueryParam("type") final String type) {

        final WorkItemQuery.Builder qb = WorkItemQuery.inbox(assignee, candidateGroups, candidateUser).toBuilder();
        if (status != null)
            qb.status(status);
        if (priority != null)
            qb.priority(priority);
        if (type != null)
            qb.type(type);

        return workItemStore.summaryByQuery(qb.build(), Instant.now());
    }

    /**
     * Projection of a root WorkItem enriched with aggregate multi-instance stats.
     * Returned by {@code GET /workitems/inbox}.
     */
    public record WorkItemRootResponse(
            WorkItemResponse item,
            int childCount,
            Integer completedCount,
            Integer requiredCount,
            String groupStatus) {

        /** Convert a {@link WorkItemRootView} to a REST response. */
        public static WorkItemRootResponse from(final WorkItemRootView view) {
            return new WorkItemRootResponse(
                    WorkItemMapper.toResponse(view.workItem()),
                    view.childCount(),
                    view.completedCount(),
                    view.requiredCount(),
                    view.groupStatus() != null ? view.groupStatus().name() : null);
        }
    }

    @GET
    @Path("/inbox")
    public List<WorkItemRootResponse> inbox(
            @QueryParam("assignee") final String assignee,
            @QueryParam("candidateGroup") final List<String> candidateGroups,
            @QueryParam("candidateUser") final String candidateUser,
            @QueryParam("status") final WorkItemStatus status,
            @QueryParam("priority") final WorkItemPriority priority,
            @QueryParam("type") final String type,
            @QueryParam("followUp") final Boolean followUp,
            @QueryParam("outcome") final String outcome) {
        Stream<WorkItemRootView> stream = workItemStore.scanRoots(assignee, candidateUser, candidateGroups).stream();
        if (status != null)   stream = stream.filter(v -> Objects.equals(v.workItem().status(), status));
        if (priority != null) stream = stream.filter(v -> Objects.equals(v.workItem().priority(), priority));
        if (type != null) {
            final io.casehub.platform.api.path.Path queryPath = io.casehub.platform.api.path.Path.parse(type);
            stream = stream.filter(v -> v.workItem().types().stream().anyMatch(t -> {
                final io.casehub.platform.api.path.Path typePath = io.casehub.platform.api.path.Path.parse(t);
                return typePath.equals(queryPath) || queryPath.isAncestorOf(typePath);
            }));
        }
        if (followUp != null) stream = stream.filter(v -> Boolean.TRUE.equals(followUp)
                ? v.workItem().followUpDate() != null
                : v.workItem().followUpDate() == null);
        if (outcome != null)  stream = stream.filter(v -> outcome.equals(v.workItem().outcome()));
        return stream.map(WorkItemRootResponse::from).toList();
    }

    @GET
    @Path("/{id}")
    public WorkItemWithAuditResponse getById(@PathParam("id") final UUID id) {
        final WorkItem wi = workItemService.findById(id)
                                                 .orElseThrow(() -> new WorkItemNotFoundException(id));
        final List<AuditEntry> trail = auditStore.findByWorkItemId(id);
        return WorkItemMapper.toWithAudit(wi, trail);
    }

    @PUT
    @Path("/{id}/claim")
    public Response claim(@PathParam("id") final UUID id,
            @QueryParam("claimant") final String claimant) {
        try {
            return Response.ok(WorkItemMapper.toResponse(workItemService.claim(id, claimant))).build();
        } catch (final IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    @PUT
    @Path("/{id}/start")
    public WorkItemResponse start(@PathParam("id") final UUID id,
            @QueryParam("actor") final String actor) {
        return WorkItemMapper.toResponse(workItemService.start(id, actor));
    }

    @PUT
    @Path("/{id}/complete")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response complete(@PathParam("id") final UUID id,
            @QueryParam("actor") final String actor,
            final CompleteRequest body) {
        final String resolution = body != null ? body.resolution() : null;
        final String outcome = body != null ? body.outcome() : null;
        try {
            return Response.ok(WorkItemMapper.toResponse(
                    workItemService.complete(id, actor, resolution, outcome))).build();
        } catch (final IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    @PUT
    @Path("/{id}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response reject(@PathParam("id") final UUID id,
            @QueryParam("actor") final String actor,
            final RejectRequest body) {
        final String reason = body != null ? body.reason() : null;
        final String outcome = body != null ? body.outcome() : null;
        try {
            return Response.ok(WorkItemMapper.toResponse(
                    workItemService.reject(id, actor, reason, outcome))).build();
        } catch (final IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    @PUT
    @Path("/{id}/delegate")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response delegate(@PathParam("id") final UUID id,
            @QueryParam("actor") final String actor,
            final DelegateRequest body) {
        try {
            return Response.ok(WorkItemMapper.toResponse(
                    workItemService.delegate(id, actor, body.to(), body.declineTarget()))).build();
        } catch (final IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    @PUT
    @Path("/{id}/accept-delegation")
    public Response acceptDelegation(@PathParam("id") final UUID id,
            @QueryParam("claimant") final String claimant) {
        try {
            return Response.ok(WorkItemMapper.toResponse(
                    workItemService.acceptDelegation(id, claimant))).build();
        } catch (final IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (final IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    @PUT
    @Path("/{id}/decline-delegation")
    public Response declineDelegation(@PathParam("id") final UUID id,
            @QueryParam("actor") final String actor) {
        try {
            return Response.ok(WorkItemMapper.toResponse(
                    workItemService.declineDelegation(id, actor))).build();
        } catch (final IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (final IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    @PUT
    @Path("/{id}/release")
    public WorkItemResponse release(@PathParam("id") final UUID id,
            @QueryParam("actor") final String actor) {
        return WorkItemMapper.toResponse(workItemService.release(id, actor));
    }

    @PUT
    @Path("/{id}/suspend")
    @Consumes(MediaType.APPLICATION_JSON)
    public WorkItemResponse suspend(@PathParam("id") final UUID id,
            @QueryParam("actor") final String actor,
            final SuspendRequest body) {
        return WorkItemMapper.toResponse(workItemService.suspend(id, actor, body != null ? body.reason() : null));
    }

    @PUT
    @Path("/{id}/resume")
    public WorkItemResponse resume(@PathParam("id") final UUID id,
            @QueryParam("actor") final String actor) {
        return WorkItemMapper.toResponse(workItemService.resume(id, actor));
    }

    @PUT
    @Path("/{id}/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    public WorkItemResponse cancel(@PathParam("id") final UUID id,
            @QueryParam("actor") final String actor,
            final CancelRequest body) {
        return WorkItemMapper.toResponse(workItemService.cancel(id, actor, body != null ? body.reason() : null));
    }

    @PUT
    @Path("/{id}/fault")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response fault(@PathParam("id") final UUID id, final FaultRequest body) {
        final WorkItem saved = workItemService.fault(id, body.actor(), body.errorDetail());
        return Response.ok(WorkItemMapper.toResponse(saved)).build();
    }

    @PUT
    @Path("/{id}/obsolete")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response obsolete(@PathParam("id") final UUID id, final ObsoleteRequest body) {
        final WorkItem saved = workItemService.obsolete(id, body.actor(), body.reason());
        return Response.ok(WorkItemMapper.toResponse(saved)).build();
    }

    @PUT
    @Path("/{id}/escalate")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response escalate(@PathParam("id") final UUID id,
            @QueryParam("actor") final String actor,
            final EscalateRequest body) {
        try {
            final WorkItem saved = workItemService.escalate(id, actor, body.targetGroup(), body.reason());
            return Response.ok(WorkItemMapper.toResponse(saved)).build();
        } catch (final IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(java.util.Map.of("error", e.getMessage())).build();
        }
    }

    @PUT
    @Path("/{id}/extend")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response extend(@PathParam("id") final UUID id,
            @QueryParam("actor") final String actor,
            final ExtendRequest body) {
        try {
            return Response.ok(WorkItemMapper.toResponse(
                    workItemService.extend(id, body != null ? body.newExpiresAt() : null, actor))).build();
        } catch (final IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    @PUT
    @Path("/{id}/deadline")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateDeadline(@PathParam("id") final UUID id,
                                   @QueryParam("actor") final String actor,
                                   final UpdateDeadlineRequest body) {
        try {
            return Response.ok(WorkItemMapper.toResponse(
                    workItemService.updateDeadline(id, body != null ? body.newDeadline() : null, actor))).build();
        } catch (final IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                           .entity(Map.of("error", e.getMessage()))
                           .build();
        } catch (final IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                           .entity(Map.of("error", e.getMessage()))
                           .build();
        }
    }


    @POST
    @Path("/{id}/labels")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addLabel(@PathParam("id") final UUID id, final AddLabelRequest request) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "path is required")).build();
        }
        try {
            final WorkItem updated = workItemService.addLabel(id, request.path(),
                    request.appliedBy() != null ? request.appliedBy() : "unknown");
            return Response.ok(WorkItemMapper.toResponse(updated)).build();
        } catch (WorkItemNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{id}/labels")
    public Response removeLabel(@PathParam("id") final UUID id,
            @QueryParam("path") final String path) {
        if (path == null || path.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "path is required")).build();
        }
        try {
            final WorkItem updated = workItemService.removeLabel(id, path);
            return Response.ok(WorkItemMapper.toResponse(updated)).build();
        } catch (WorkItemNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", e.getMessage())).build();
        } catch (LabelNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", e.getMessage())).build();
        }
    }

    /** Request body for cloning a WorkItem. */
    public record CloneRequest(String title, String createdBy) {
    }

    /**
     * Clone a WorkItem — creates a new PENDING WorkItem copying operational fields.
     *
     * <p>
     * Title defaults to "{original title} (copy)" if not overridden.
     * MANUAL labels are copied; INFERRED labels are not (the filter engine re-applies on the first event).
     * Assignee, owner, delegation state, and resolution are never copied.
     *
     * @param id the source WorkItem UUID
     * @param request optional title override and required createdBy
     * @return 201 Created with the new WorkItem, 404 if source not found
     */
    @POST
    @Path("/{id}/clone")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response clone(@PathParam("id") final UUID id, final CloneRequest request) {
        try {
            final String createdBy = (request != null && request.createdBy() != null)
                    ? request.createdBy()
                    : "unknown";
            final String         title = (request != null) ? request.title() : null;
            final WorkItem clone = workItemService.clone(id, title, createdBy);
            return Response.status(Response.Status.CREATED).entity(WorkItemMapper.toResponse(clone)).build();
        } catch (WorkItemNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();
        }
    }

    // ── SSE event streams ─────────────────────────────────────────────────────

    /**
     * Server-Sent Events stream of all WorkItem lifecycle events.
     *
     * <p>
     * Connect and receive real-time notifications as WorkItems transition through
     * their lifecycle — no polling required. This is the REST equivalent of the
     * CDI {@link WorkItemLifecycleEvent} that internal beans observe.
     *
     * <p>
     * <strong>Hot stream:</strong> only events that occur after the client connects
     * are delivered. Past events are not replayed — use {@code GET /workitems/{id}}
     * to fetch current state.
     *
     * @param workItemId if provided, only events for this WorkItem are emitted
     * @param type if provided, only events matching this type suffix are emitted
     *        (e.g. {@code "created"}, {@code "completed"}; case-insensitive)
     */
    @GET
    @Path("/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<WorkItemLifecycleEvent> streamEvents(
            @QueryParam("workItemId") final UUID workItemId,
            @QueryParam("type") final String type) {
        return broadcaster.stream(workItemId, type, currentPrincipal.tenancyId());
    }

    /**
     * Server-Sent Events stream scoped to a specific WorkItem.
     *
     * <p>
     * Convenience alias for {@code GET /workitems/events?workItemId={id}}.
     * Useful for dashboards that track a single WorkItem's progress.
     *
     * @param id the WorkItem UUID
     */
    @GET
    @Path("/{id}/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<WorkItemLifecycleEvent> streamWorkItemEvents(@PathParam("id") final UUID id) {
        return broadcaster.stream(id, null, currentPrincipal.tenancyId());
    }

    // ── Notes ─────────────────────────────────────────────────────────────────

    /** Request body for adding or editing a {@link WorkItemNote}. */
    public record NoteRequest(String content, String author) {
    }

    /** Request body for editing an existing note (author is immutable). */
    public record NoteEditRequest(String content) {
    }

    /**
     * Add an internal operational note to a WorkItem.
     *
     * @param id the WorkItem UUID
     * @param request {@code content} (required) and {@code author} (required)
     * @return 201 Created with the new note, 400 if content or author is missing
     */
    @POST
    @Path("/{id}/notes")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response addNote(@PathParam("id") final UUID id, final NoteRequest request) {
        if (request == null || request.content() == null || request.content().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "content is required")).build();
        }
        if (request.author() == null || request.author().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "author is required")).build();
        }
        final WorkItemNote note = new WorkItemNote();
        note.workItemId = id;
        note.content = request.content();
        note.author = request.author();
        return Response.status(Response.Status.CREATED)
                .entity(toNoteResponse(noteStore.append(note))).build();
    }

    /**
     * List all notes for a WorkItem, oldest first.
     *
     * @param id the WorkItem UUID
     * @return 200 OK with chronological list; may be empty
     */
    @GET
    @Path("/{id}/notes")
    public List<Map<String, Object>> listNotes(@PathParam("id") final UUID id) {
        return noteStore.findByWorkItemId(id).stream().map(this::toNoteResponse).toList();
    }

    /**
     * Edit an existing note's content. Sets {@code editedAt} to now.
     *
     * @param id the WorkItem UUID
     * @param noteId the note UUID
     * @param request new {@code content} (required)
     * @return 200 OK with updated note, 400 if content blank, 404 if not found
     */
    @PUT
    @Path("/{id}/notes/{noteId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response editNote(
            @PathParam("id") final UUID id,
            @PathParam("noteId") final UUID noteId,
            final NoteEditRequest request) {
        if (request == null || request.content() == null || request.content().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "content is required")).build();
        }
        return noteStore.findById(noteId)
                .filter(n -> n.workItemId.equals(id))
                .map(n -> {
                    n.content = request.content();
                    n.editedAt = java.time.Instant.now();
                    return Response.ok(toNoteResponse(noteStore.update(n))).build();
                })
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Note not found")).build());
    }

    /**
     * Delete a note.
     *
     * @param id the WorkItem UUID
     * @param noteId the note UUID
     * @return 204 No Content on success, 404 if not found
     */
    @DELETE
    @Path("/{id}/notes/{noteId}")
    @Transactional
    public Response deleteNote(
            @PathParam("id") final UUID id,
            @PathParam("noteId") final UUID noteId) {
        final boolean existed = noteStore.findById(noteId)
                .filter(n -> n.workItemId.equals(id))
                .map(n -> noteStore.delete(noteId))
                .orElse(false);
        return existed
                ? Response.noContent().build()
                : Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Note not found")).build();
    }

    private Map<String, Object> toNoteResponse(final WorkItemNote note) {
        final Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", note.id);
        m.put("workItemId", note.workItemId);
        m.put("content", note.content);
        m.put("author", note.author);
        m.put("createdAt", note.createdAt);
        m.put("editedAt", note.editedAt);
        return m;
    }

    // ── Relation graph convenience ─────────────────────────────────────────────

    /**
     * Return all direct children of this WorkItem — items that have a
     * {@link WorkItemRelationType#PART_OF} relation pointing here.
     *
     * <p>
     * Returns direct children only; does not recurse into grandchildren.
     * For deep tree traversal, walk the children endpoint repeatedly.
     *
     * @param parentId the parent WorkItem UUID
     * @return 200 OK with list of child WorkItems (as WorkItemResponse); may be empty
     */
    @GET
    @Path("/{id}/children")
    public List<WorkItemResponse> children(@PathParam("id") final UUID parentId) {
        return relationStore.findByTargetAndType(parentId, WorkItemRelationType.PART_OF)
                .stream()
                .map(r -> workItemStore.get(r.sourceId).orElse(null))
                .filter(wi -> wi != null)
                .map(WorkItemMapper::toResponse)
                .toList();
    }

    /**
     * Return the parent WorkItem — the item this one is
     * {@link WorkItemRelationType#PART_OF}, if any.
     *
     * <p>
     * A WorkItem has at most one parent (one outgoing PART_OF relation).
     * If the WorkItem has no parent (it is a root), returns 404.
     *
     * @param childId the child WorkItem UUID
     * @return 200 OK with the parent WorkItemResponse, 404 if no parent exists
     */
    @GET
    @Path("/{id}/parent")
    public Response parent(@PathParam("id") final UUID childId) {
        return relationStore.findBySourceAndType(childId, WorkItemRelationType.PART_OF)
                .stream().findFirst()
                .flatMap(r -> workItemStore.get(r.targetId))
                .map(wi -> Response.ok(WorkItemMapper.toResponse(wi)).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "No parent — this WorkItem has no PART_OF relation")).build());
    }

    // ── Links ─────────────────────────────────────────────────────────────────

    /**
     * Request body for adding a link to an external resource.
     *
     * @param url the URL of the external resource (required)
     * @param title optional human-readable display title
     * @param relationType the link type — use {@link WorkItemLinkType} constants or any
     *        custom string (required)
     * @param linkedBy the actor adding this link
     */
    public record AddLinkRequest(String url, String title, String relationType, String linkedBy) {
    }

    /**
     * Add a structured reference to an external resource.
     *
     * <p>
     * Any non-blank string is accepted as {@code relationType} — use
     * {@link WorkItemLinkType} constants for well-known types, or define your own
     * ({@code "runbook"}, {@code "customer-ticket"}, {@code "internal-wiki"}).
     *
     * @param id the WorkItem UUID
     * @param request the link to add
     * @return 201 Created with the link, 400 if url or relationType is blank
     */
    @POST
    @Path("/{id}/links")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response addLink(@PathParam("id") final UUID id, final AddLinkRequest request) {
        if (request == null || request.url() == null || request.url().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "url is required")).build();
        }
        if (request.relationType() == null || request.relationType().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "relationType is required")).build();
        }
        final WorkItemLink link = new WorkItemLink();
        link.workItemId = id;
        link.url = request.url();
        link.title = request.title();
        link.relationType = request.relationType();
        link.linkedBy = request.linkedBy() != null ? request.linkedBy() : "unknown";
        linkStore.put(link);
        return Response.status(Response.Status.CREATED).entity(toLinkResponse(link)).build();
    }

    /**
     * List all links on a WorkItem, optionally filtered by relation type.
     *
     * @param id the WorkItem UUID
     * @param type if provided, only links with this relationType are returned
     * @return 200 OK with links ordered by creation time; may be empty
     */
    @GET
    @Path("/{id}/links")
    public List<Map<String, Object>> listLinks(
            @PathParam("id") final UUID id,
            @QueryParam("type") final String type) {
        final List<WorkItemLink> links = (type != null && !type.isBlank())
                ? linkStore.findByWorkItemIdAndType(id, type)
                : linkStore.findByWorkItemId(id);
        return links.stream().map(this::toLinkResponse).toList();
    }

    /**
     * Remove a link from a WorkItem.
     *
     * <p>
     * Does not affect the external resource — only the local reference record is deleted.
     *
     * @param id the WorkItem UUID (for ownership check)
     * @param linkId the link UUID
     * @return 204 No Content on success, 404 if not found
     */
    @DELETE
    @Path("/{id}/links/{linkId}")
    @Transactional
    public Response deleteLink(
            @PathParam("id") final UUID id,
            @PathParam("linkId") final UUID linkId) {
        final java.util.Optional<WorkItemLink> linkOpt = linkStore.get(linkId);
        if (linkOpt.isEmpty() || !linkOpt.get().workItemId.equals(id)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Link not found")).build();
        }
        linkStore.delete(linkId);
        return Response.noContent().build();
    }

    private Map<String, Object> toLinkResponse(final WorkItemLink link) {
        final java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", link.id);
        m.put("workItemId", link.workItemId);
        m.put("url", link.url);
        m.put("title", link.title);
        m.put("relationType", link.relationType);
        m.put("linkedBy", link.linkedBy);
        m.put("createdAt", link.createdAt);
        return m;
    }
}
