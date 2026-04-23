package io.quarkiverse.workitems.examples.escalation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.logging.Logger;

import io.quarkiverse.workitems.examples.StepLog;
import io.quarkiverse.workitems.runtime.api.AuditEntryResponse;
import io.quarkiverse.workitems.runtime.model.AuditEntry;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.workitems.runtime.model.WorkItemPriority;
import io.quarkiverse.workitems.runtime.repository.AuditEntryStore;
import io.quarkiverse.workitems.runtime.repository.WorkItemStore;
import io.quarkiverse.workitems.runtime.service.ExpiryCleanupJob;
import io.quarkiverse.workitems.runtime.service.WorkItemService;

/**
 * Scenario 8 — Escalation: expiry-triggered status transition and audit event.
 *
 * <p>
 * A critical production incident WorkItem is created with an {@code expiresAt} value in
 * the past, simulating a deadline that has already passed. The scenario then calls
 * {@link ExpiryCleanupJob#checkExpired()} programmatically to drive the expiry transition
 * without waiting for the scheduler. The resulting {@code EXPIRED} status and audit event
 * confirm that the escalation pipeline fired correctly.
 *
 * <p>
 * Transaction discipline: the endpoint method is intentionally <em>not</em> annotated with
 * {@code @Transactional}. Each step commits independently:
 * <ol>
 * <li>{@code workItemService.create()} — commits the new WorkItem in its own transaction
 * <li>{@code expiryJob.checkExpired()} — opens its own transaction, sees the committed
 * item, transitions it to {@code EXPIRED}, and commits the state change
 * <li>Audit and store reads — see the fully committed final state
 * </ol>
 *
 * <p>
 * Actors: {@code alerting-system} (creator), {@code oncall-engineer} (candidate, never claims).
 *
 * <p>
 * Endpoint: {@code POST /examples/escalation/run}
 */
@Path("/examples/escalation")
@Produces(MediaType.APPLICATION_JSON)
public class EscalationScenario {

    private static final Logger LOG = Logger.getLogger(EscalationScenario.class);

    private static final String SCENARIO_ID = "expiry-escalation";
    private static final String ACTOR_CREATOR = "alerting-system";

    @Inject
    WorkItemService workItemService;

    @Inject
    ExpiryCleanupJob expiryJob;

    @Inject
    WorkItemStore workItemStore;

    @Inject
    AuditEntryStore auditStore;

    /**
     * Run the escalation scenario end to end and return the audit outcome.
     *
     * @return scenario response confirming EXPIRED status and audit event presence
     */
    @POST
    @Path("/run")
    public EscalationResponse run() {
        final List<StepLog> steps = new ArrayList<>();
        final int total = 4;

        // Step 1: create an incident WorkItem with an expiry already in the past
        final String description1 = "alerting-system creates production incident WorkItem with expiresAt 10 seconds in the past";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 1, total, description1);
        final WorkItemCreateRequest request = new WorkItemCreateRequest(
                "Production incident: payment service 500 errors",
                "Critical: payment service returning HTTP 500 for all transactions since 03:14 UTC. "
                        + "Revenue impact: ~£2,000/min. Immediate action required.",
                "incident",
                null,
                WorkItemPriority.CRITICAL,
                null,
                null,
                "oncall-engineer",
                null,
                ACTOR_CREATOR,
                "{\"service\": \"payment-service\", \"errorRate\": \"100%\", \"since\": \"03:14 UTC\"}",
                null,
                Instant.now().minusSeconds(10), // already expired
                null,
                null,
                null);
        final WorkItem wi = workItemService.create(request);
        steps.add(new StepLog(1, description1, wi.id));

        // Step 2: trigger the expiry cleanup job (simulates the scheduler firing)
        final String description2 = "ExpiryCleanupJob.checkExpired() processes the already-expired WorkItem";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 2, total, description2);
        expiryJob.checkExpired();
        steps.add(new StepLog(2, description2, wi.id));

        // Step 3: reload the WorkItem and verify EXPIRED status
        final String description3 = "Reload WorkItem from store — confirm status is EXPIRED";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 3, total, description3);
        final WorkItem reloaded = workItemStore.get(wi.id)
                .orElseThrow(() -> new IllegalStateException("WorkItem not found after expiry: " + wi.id));
        final String finalStatus = reloaded.status.name();
        steps.add(new StepLog(3, description3 + " — status=" + finalStatus, wi.id));

        // Step 4: collect audit trail and confirm EXPIRED event is present
        final String description4 = "Collect audit trail confirming CREATED → EXPIRED event sequence";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 4, total, description4);
        final List<AuditEntry> auditEntries = auditStore.findByWorkItemId(wi.id);
        final boolean escalationEventPresent = auditEntries.stream()
                .anyMatch(e -> "EXPIRED".equals(e.event));
        final List<AuditEntryResponse> auditTrail = auditEntries.stream()
                .map(a -> new AuditEntryResponse(a.id, a.event, a.actor, a.detail, a.occurredAt))
                .toList();
        steps.add(new StepLog(4,
                description4 + " — " + auditEntries.size() + " entries, EXPIRED present: " + escalationEventPresent,
                wi.id));

        return new EscalationResponse(
                SCENARIO_ID,
                steps,
                wi.id,
                finalStatus,
                escalationEventPresent,
                auditTrail);
    }
}
