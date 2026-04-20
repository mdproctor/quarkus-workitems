package io.quarkiverse.workitems.runtime.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import io.quarkiverse.workitems.runtime.model.AuditEntry;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemPriority;
import io.quarkiverse.workitems.runtime.repository.AuditEntryStore;
import io.quarkiverse.workitems.runtime.repository.AuditQuery;

/**
 * Compliance and performance reporting endpoints.
 *
 * <pre>
 * GET /workitems/reports/sla-breaches?from=&to=&category=&priority=
 *     — WorkItems that missed their expiresAt deadline, with breach duration + aggregates
 *
 * GET /workitems/reports/actors/{actorId}?from=&to=&category=
 *     — Performance summary for a single actor derived from audit history
 * </pre>
 *
 * @see <a href="https://github.com/mdproctor/quarkus-workitems/issues/110">Issue #110</a>
 * @see <a href="https://github.com/mdproctor/quarkus-workitems/issues/111">Issue #111</a>
 * @see <a href="https://github.com/mdproctor/quarkus-workitems/issues/99">Epic #99</a>
 */
@Path("/workitems/reports")
@Produces(MediaType.APPLICATION_JSON)
public class ReportResource {

    @Inject
    EntityManager em;

    @Inject
    AuditEntryStore auditStore;

    // ── SLA Breach Report ──────────────────────────────────────────────────────

    /**
     * Returns WorkItems that breached their {@code expiresAt} deadline.
     *
     * <p>
     * A WorkItem is "breached" when:
     * <ul>
     * <li>It is still open (PENDING/ASSIGNED/IN_PROGRESS) and {@code expiresAt} is in the past, OR
     * <li>It was completed/rejected but {@code completedAt} is after {@code expiresAt}.
     * </ul>
     *
     * @param from include only WorkItems whose {@code expiresAt} is on or after this (ISO 8601)
     * @param to include only WorkItems whose {@code expiresAt} is on or before this (ISO 8601)
     * @param category optional category filter
     * @param priority optional priority filter
     */
    @GET
    @Path("/sla-breaches")
    public Map<String, Object> slaBreaches(
            @QueryParam("from") final String from,
            @QueryParam("to") final String to,
            @QueryParam("category") final String category,
            @QueryParam("priority") final String priority) {

        final Instant now = Instant.now();
        final Instant fromInstant = from != null ? Instant.parse(from) : null;
        final Instant toInstant = to != null ? Instant.parse(to) : null;

        final List<WorkItem> breached = findBreachedWorkItems(now, fromInstant, toInstant, category, priority);

        // Build response items
        final List<Map<String, Object>> items = new ArrayList<>();
        double totalBreachMinutes = 0;
        final Map<String, Integer> byCategory = new HashMap<>();

        for (final WorkItem wi : breached) {
            final Instant breachEnd = wi.completedAt != null ? wi.completedAt : now;
            final long breachMinutes = ChronoUnit.MINUTES.between(wi.expiresAt, breachEnd);

            final Map<String, Object> item = new LinkedHashMap<>();
            item.put("workItemId", wi.id);
            item.put("category", wi.category);
            item.put("priority", wi.priority != null ? wi.priority.name() : null);
            item.put("expiresAt", wi.expiresAt);
            item.put("completedAt", wi.completedAt);
            item.put("status", wi.status != null ? wi.status.name() : null);
            item.put("breachDurationMinutes", Math.max(0, breachMinutes));
            items.add(item);

            totalBreachMinutes += Math.max(0, breachMinutes);
            if (wi.category != null) {
                byCategory.merge(wi.category, 1, Integer::sum);
            }
        }

        final double avgBreachMinutes = breached.isEmpty() ? 0.0 : totalBreachMinutes / breached.size();

        final Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalBreached", breached.size());
        summary.put("avgBreachDurationMinutes", Math.round(avgBreachMinutes * 10.0) / 10.0);
        summary.put("byCategory", byCategory);

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("summary", summary);
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<WorkItem> findBreachedWorkItems(
            final Instant now, final Instant from, final Instant to,
            final String category, final String priority) {

        final StringBuilder jpql = new StringBuilder(
                "SELECT w FROM WorkItem w WHERE (");

        // Breach condition: open items past expiry OR completed/rejected late
        jpql.append(
                "(w.status IN ('PENDING','ASSIGNED','IN_PROGRESS','SUSPENDED','ESCALATED','EXPIRED') AND w.expiresAt < :now)");
        jpql.append(" OR ");
        jpql.append(
                "(w.status IN ('COMPLETED','REJECTED','CANCELLED') AND w.completedAt IS NOT NULL AND w.completedAt > w.expiresAt)");
        jpql.append(")");

        if (from != null) {
            jpql.append(" AND w.expiresAt >= :from");
        }
        if (to != null) {
            jpql.append(" AND w.expiresAt <= :to");
        }
        if (category != null && !category.isBlank()) {
            jpql.append(" AND w.category = :category");
        }
        if (priority != null && !priority.isBlank()) {
            jpql.append(" AND w.priority = :priority");
        }
        jpql.append(" ORDER BY w.expiresAt DESC");

        final jakarta.persistence.TypedQuery<WorkItem> q = em.createQuery(jpql.toString(), WorkItem.class);
        q.setParameter("now", now);
        if (from != null)
            q.setParameter("from", from);
        if (to != null)
            q.setParameter("to", to);
        if (category != null && !category.isBlank())
            q.setParameter("category", category);
        if (priority != null && !priority.isBlank()) {
            q.setParameter("priority", WorkItemPriority.valueOf(priority));
        }

        return q.getResultList();
    }

    // ── Actor Performance Report ───────────────────────────────────────────────

    /**
     * Returns a performance summary for a single actor derived from audit history.
     *
     * <p>
     * Counts are derived from {@link AuditEntry} records where the actor matches:
     * <ul>
     * <li>{@code totalAssigned}: ASSIGNED events for this actor
     * <li>{@code totalCompleted}: COMPLETED events for this actor
     * <li>{@code totalRejected}: REJECTED events for this actor
     * <li>{@code avgCompletionMinutes}: average of (completedAt − assignedAt) across
     * WorkItems completed by this actor; null if no completions
     * <li>{@code byCategory}: count of completed WorkItems per category
     * </ul>
     *
     * @param actorId the actor to report on (path parameter)
     * @param from include only events on or after this instant (ISO 8601)
     * @param to include only events on or before this instant (ISO 8601)
     * @param category optional category scope
     */
    @GET
    @Path("/actors/{actorId}")
    public Map<String, Object> actorPerformance(
            @PathParam("actorId") final String actorId,
            @QueryParam("from") final String from,
            @QueryParam("to") final String to,
            @QueryParam("category") final String category) {

        final Instant fromInstant = from != null ? Instant.parse(from) : null;
        final Instant toInstant = to != null ? Instant.parse(to) : null;

        final int totalAssigned = countEvents(actorId, "ASSIGNED", fromInstant, toInstant, category);
        final int totalCompleted = countEvents(actorId, "COMPLETED", fromInstant, toInstant, category);
        final int totalRejected = countEvents(actorId, "REJECTED", fromInstant, toInstant, category);

        // avgCompletionMinutes: for completed WorkItems, average (completedAt - assignedAt)
        final Double avgCompletionMinutes = computeAvgCompletionMinutes(
                actorId, fromInstant, toInstant, category);

        // byCategory: completed WorkItems per category
        final Map<String, Integer> byCategory = computeByCategory(
                actorId, fromInstant, toInstant, category);

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("actorId", actorId);
        result.put("totalAssigned", totalAssigned);
        result.put("totalCompleted", totalCompleted);
        result.put("totalRejected", totalRejected);
        result.put("avgCompletionMinutes", avgCompletionMinutes);
        result.put("byCategory", byCategory);
        return result;
    }

    private int countEvents(final String actorId, final String event,
            final Instant from, final Instant to, final String category) {
        final AuditQuery.Builder qb = AuditQuery.builder()
                .actorId(actorId).event(event).size(1).from(from).to(to).category(category);
        return (int) auditStore.count(qb.build());
    }

    private Double computeAvgCompletionMinutes(
            final String actorId, final Instant from, final Instant to, final String category) {

        // Compute in Java — avoids SQL DATEDIFF portability issues (H2 vs PostgreSQL)
        final StringBuilder jpql = new StringBuilder(
                "SELECT w FROM WorkItem w WHERE w.assigneeId = :actorId" +
                        " AND w.completedAt IS NOT NULL AND w.assignedAt IS NOT NULL");

        if (from != null)
            jpql.append(" AND w.completedAt >= :from");
        if (to != null)
            jpql.append(" AND w.completedAt <= :to");
        if (category != null && !category.isBlank())
            jpql.append(" AND w.category = :category");

        final jakarta.persistence.TypedQuery<WorkItem> q = em.createQuery(jpql.toString(), WorkItem.class);
        q.setParameter("actorId", actorId);
        if (from != null)
            q.setParameter("from", from);
        if (to != null)
            q.setParameter("to", to);
        if (category != null && !category.isBlank())
            q.setParameter("category", category);

        final List<WorkItem> completed = q.getResultList();
        if (completed.isEmpty()) {
            return null;
        }

        final double avg = completed.stream()
                .mapToLong(w -> ChronoUnit.MINUTES.between(w.assignedAt, w.completedAt))
                .average()
                .orElse(0.0);

        return Math.round(avg * 10.0) / 10.0;
    }

    private Map<String, Integer> computeByCategory(
            final String actorId, final Instant from, final Instant to, final String category) {

        // Get all workItemIds completed by this actor from audit entries, then look up categories
        final AuditQuery.Builder qb = AuditQuery.builder()
                .actorId(actorId).event("COMPLETED").size(100).from(from).to(to).category(category);

        final List<AuditEntry> completedEntries = auditStore.query(qb.build());
        final Map<String, Integer> result = new HashMap<>();

        for (final AuditEntry entry : completedEntries) {
            final WorkItem wi = em.find(WorkItem.class, entry.workItemId);
            if (wi != null && wi.category != null) {
                result.merge(wi.category, 1, Integer::sum);
            }
        }
        return result;
    }
}
