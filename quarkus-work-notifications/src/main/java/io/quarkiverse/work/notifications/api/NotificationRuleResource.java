package io.quarkiverse.work.notifications.api;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import io.quarkiverse.work.notifications.model.WorkItemNotificationRule;

/**
 * REST CRUD for {@link WorkItemNotificationRule}.
 *
 * <pre>
 * POST   /workitem-notification-rules          create a rule
 * GET    /workitem-notification-rules          list all rules (optionally filter by channelType)
 * GET    /workitem-notification-rules/{id}     get a specific rule
 * PUT    /workitem-notification-rules/{id}     update (enable/disable, change URL)
 * DELETE /workitem-notification-rules/{id}     delete
 * </pre>
 */
@Path("/workitem-notification-rules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NotificationRuleResource {

    public record CreateRuleRequest(
            String channelType,
            String targetUrl,
            String eventTypes,
            String category,
            String secret,
            Boolean enabled) {
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @POST
    @Transactional
    public Response create(final CreateRuleRequest req) {
        if (req == null || req.channelType() == null || req.channelType().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "channelType is required")).build();
        }
        if (req.targetUrl() == null || req.targetUrl().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "targetUrl is required")).build();
        }
        if (req.eventTypes() == null || req.eventTypes().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "eventTypes is required")).build();
        }

        final WorkItemNotificationRule rule = new WorkItemNotificationRule();
        rule.channelType = req.channelType();
        rule.targetUrl = req.targetUrl();
        rule.eventTypes = req.eventTypes();
        rule.category = req.category();
        rule.secret = req.secret();
        rule.enabled = req.enabled() == null || req.enabled();
        rule.persist();

        return Response.created(URI.create("/workitem-notification-rules/" + rule.id))
                .entity(toResponse(rule)).build();
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @GET
    public List<Map<String, Object>> list(
            @QueryParam("channelType") final String channelType) {
        final List<WorkItemNotificationRule> rules = channelType != null
                ? WorkItemNotificationRule.list("channelType = ?1 ORDER BY createdAt ASC", channelType)
                : WorkItemNotificationRule.findAllEnabled();
        return rules.stream().map(this::toResponse).toList();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") final UUID id) {
        final WorkItemNotificationRule rule = WorkItemNotificationRule.findById(id);
        if (rule == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Rule not found")).build();
        }
        return Response.ok(toResponse(rule)).build();
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @PUT
    @Path("/{id}")
    @Transactional
    public Response update(@PathParam("id") final UUID id, final CreateRuleRequest req) {
        final WorkItemNotificationRule rule = WorkItemNotificationRule.findById(id);
        if (rule == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Rule not found")).build();
        }
        if (req.channelType() != null && !req.channelType().isBlank()) {
            rule.channelType = req.channelType();
        }
        if (req.targetUrl() != null && !req.targetUrl().isBlank()) {
            rule.targetUrl = req.targetUrl();
        }
        if (req.eventTypes() != null && !req.eventTypes().isBlank()) {
            rule.eventTypes = req.eventTypes();
        }
        if (req.category() != null) {
            rule.category = req.category().isBlank() ? null : req.category();
        }
        if (req.secret() != null) {
            rule.secret = req.secret().isBlank() ? null : req.secret();
        }
        if (req.enabled() != null) {
            rule.enabled = req.enabled();
        }
        rule.persist();
        return Response.ok(toResponse(rule)).build();
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") final UUID id) {
        final WorkItemNotificationRule rule = WorkItemNotificationRule.findById(id);
        if (rule == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Rule not found")).build();
        }
        rule.delete();
        return Response.noContent().build();
    }

    // ── mapping ───────────────────────────────────────────────────────────────

    private Map<String, Object> toResponse(final WorkItemNotificationRule rule) {
        return Map.of(
                "id", rule.id.toString(),
                "channelType", rule.channelType,
                "targetUrl", rule.targetUrl,
                "eventTypes", rule.eventTypes,
                "category", rule.category != null ? rule.category : "",
                "enabled", rule.enabled,
                "createdAt", rule.createdAt.toString());
        // secret intentionally omitted from responses
    }
}
