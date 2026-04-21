package io.quarkiverse.workitems.filterregistry.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import io.quarkiverse.workitems.filterregistry.model.FilterRule;
import io.quarkiverse.workitems.filterregistry.registry.PermanentFilterRegistry;
import io.quarkiverse.workitems.filterregistry.spi.FilterDefinition;

/**
 * REST resource for dynamic (DB-persisted) and permanent (CDI-produced) filter rules.
 *
 * <pre>
 * POST   /filter-rules                          — create dynamic rule
 * GET    /filter-rules                          — list all dynamic rules
 * GET    /filter-rules/{id}                     — get single dynamic rule; 404 if not found
 * DELETE /filter-rules/{id}                     — delete dynamic rule; 204/404
 * GET    /filter-rules/permanent                    — list all permanent (CDI-produced) rules
 * PUT    /filter-rules/permanent/enabled?name=... — toggle permanent rule at runtime
 * </pre>
 */
@Path("/filter-rules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FilterRuleResource {

    @Inject
    PermanentFilterRegistry permanentRegistry;

    /** Request body for creating a dynamic filter rule. */
    public record CreateFilterRuleRequest(
            String name,
            String description,
            Boolean enabled,
            String condition,
            List<String> events,
            String actionsJson) {
    }

    /**
     * Creates a new dynamic filter rule.
     *
     * @param req the rule definition
     * @return 201 with the persisted rule, or 400 if name or condition is missing
     */
    @POST
    @Transactional
    public Response create(final CreateFilterRuleRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            return Response.status(400).entity(Map.of("error", "name required")).build();
        }
        if (req.condition() == null || req.condition().isBlank()) {
            return Response.status(400).entity(Map.of("error", "condition required")).build();
        }

        final FilterRule rule = new FilterRule();
        rule.name = req.name();
        rule.description = req.description();
        rule.enabled = req.enabled() != null ? req.enabled() : true;
        rule.condition = req.condition();
        rule.events = req.events() != null && !req.events().isEmpty()
                ? String.join(",", req.events())
                : "ADD,UPDATE,REMOVE";
        rule.actionsJson = req.actionsJson() != null ? req.actionsJson() : "[]";
        rule.persist();
        return Response.status(201).entity(toResponse(rule)).build();
    }

    /**
     * Lists all dynamic filter rules.
     *
     * @return list of all rules
     */
    @GET
    public List<Map<String, Object>> list() {
        return FilterRule.<FilterRule> listAll().stream().map(this::toResponse).toList();
    }

    /**
     * Gets a single dynamic filter rule by ID.
     *
     * @param id the rule UUID
     * @return 200 with the rule, or 404 if not found
     */
    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") final UUID id) {
        final FilterRule rule = FilterRule.findById(id);
        return rule != null ? Response.ok(toResponse(rule)).build()
                : Response.status(404).entity(Map.of("error", "Not found")).build();
    }

    /**
     * Deletes a dynamic filter rule by ID.
     *
     * @param id the rule UUID
     * @return 204 if deleted, 404 if not found
     */
    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") final UUID id) {
        return FilterRule.deleteById(id) ? Response.noContent().build()
                : Response.status(404).entity(Map.of("error", "Not found")).build();
    }

    /**
     * Lists all permanent (CDI-produced) filter rules.
     *
     * @return list of permanent rules with current enabled state
     */
    @GET
    @Path("/permanent")
    public List<Map<String, Object>> listPermanent() {
        return permanentRegistry.all().stream().map(this::toPermResponse).toList();
    }

    /**
     * Toggles the enabled state of a permanent filter rule at runtime.
     * The override is held in-memory and resets on restart.
     * The filter name is passed as a query parameter to avoid slash-encoding issues with
     * namespaced names like {@code test/apply-label}.
     *
     * @param name the filter name (query param)
     * @param body map containing {@code enabled} boolean
     * @return 200 with updated state, 400 if enabled or name missing, 404 if not found
     */
    @PUT
    @Path("/permanent/enabled")
    public Response togglePermanent(@QueryParam("name") final String name,
            final Map<String, Boolean> body) {
        if (name == null || name.isBlank()) {
            return Response.status(400).entity(Map.of("error", "name query parameter required")).build();
        }
        final Boolean enabled = body != null ? body.get("enabled") : null;
        if (enabled == null) {
            return Response.status(400).entity(Map.of("error", "enabled required")).build();
        }
        if (permanentRegistry.findByName(name).isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Not found")).build();
        }
        permanentRegistry.setEnabled(name, enabled);
        return Response.ok(Map.of("name", name, "enabled", enabled)).build();
    }

    private Map<String, Object> toResponse(final FilterRule r) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("name", r.name);
        m.put("description", r.description);
        m.put("enabled", r.enabled);
        m.put("condition", r.condition);
        m.put("events", r.events);
        m.put("actionsJson", r.actionsJson);
        m.put("createdAt", r.createdAt);
        return m;
    }

    private Map<String, Object> toPermResponse(final FilterDefinition d) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", d.name());
        m.put("description", d.description());
        m.put("enabled", d.enabled());
        m.put("events", d.events().stream().map(Enum::name).toList());
        m.put("condition", d.condition());
        return m;
    }
}
