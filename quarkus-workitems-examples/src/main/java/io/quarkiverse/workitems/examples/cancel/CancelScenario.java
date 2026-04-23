package io.quarkiverse.workitems.examples.cancel;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
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
import io.quarkiverse.workitems.runtime.service.WorkItemService;

/**
 * Scenario 6 — Software Licence Cancellation.
 *
 * <p>
 * Demonstrates the cancellation path: a procurement WorkItem is created but
 * cancelled before anyone claims it because the purchase is no longer needed.
 *
 * <p>
 * Story: An employee submits a software licence request for a JetBrains IDE.
 * Before anyone claims it, the IT manager realises the purchase can be covered
 * by an existing bulk licence — the individual request is no longer needed.
 * The manager cancels it with a reason.
 *
 * <p>
 * Actors:
 * <ul>
 * <li>{@code procurement-bot} — system that raises the licence request</li>
 * <li>{@code it-manager} — cancels the redundant request</li>
 * </ul>
 *
 * <p>
 * Endpoint: {@code POST /examples/cancel/run}
 */
@Path("/examples/cancel")
@Produces(MediaType.APPLICATION_JSON)
public class CancelScenario {

    private static final Logger LOG = Logger.getLogger(CancelScenario.class);

    private static final String SCENARIO_ID = "licence-cancel";
    private static final String ACTOR_CREATOR = "procurement-bot";
    private static final String ACTOR_MANAGER = "it-manager";
    private static final String CANCEL_REASON = "Covered by existing enterprise bulk licence EL-2026-047";

    @Inject
    WorkItemService workItemService;

    @Inject
    AuditEntryStore auditStore;

    /**
     * Run the licence cancellation scenario from end to end and return the result.
     *
     * @return scenario response containing steps, cancellation details, and audit trail
     */
    @POST
    @Path("/run")
    @Transactional
    public CancelResponse run() {
        final List<StepLog> steps = new ArrayList<>();
        final int total = 3;

        // Step 1: procurement-bot creates the software licence request
        final String description1 = "procurement-bot creates software licence request for JetBrains IDE";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 1, total, description1);

        final WorkItemCreateRequest request = new WorkItemCreateRequest(
                "Software licence request: JetBrains IDE",
                "Individual developer licence for IntelliJ IDEA Ultimate Edition",
                "procurement",
                null,
                WorkItemPriority.NORMAL,
                null,
                null,
                ACTOR_MANAGER,
                null,
                ACTOR_CREATOR,
                "{\"product\": \"IntelliJ IDEA Ultimate\", \"seats\": 1}",
                null,
                null,
                null,
                null,
                null);

        final WorkItem wi = workItemService.create(request);
        steps.add(new StepLog(1, description1, wi.id));

        // Step 2: it-manager discovers the request — bulk licence already covers this
        final String description2 = "it-manager discovers existing bulk licence EL-2026-047 covers this request";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 2, total, description2);
        steps.add(new StepLog(2, description2, wi.id));

        // Step 3: it-manager cancels the WorkItem with a reason
        final String description3 = "it-manager cancels the licence request — redundant under bulk licence";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 3, total, description3);
        final WorkItem cancelled = workItemService.cancel(wi.id, ACTOR_MANAGER, CANCEL_REASON);
        steps.add(new StepLog(3, description3, wi.id));

        // Collect audit trail
        final List<AuditEntry> auditEntries = auditStore.findByWorkItemId(wi.id);
        final List<AuditEntryResponse> auditTrail = auditEntries.stream()
                .map(a -> new AuditEntryResponse(a.id, a.event, a.actor, a.detail, a.occurredAt))
                .toList();

        return new CancelResponse(
                SCENARIO_ID,
                steps,
                wi.id,
                ACTOR_MANAGER,
                CANCEL_REASON,
                cancelled.status.name(),
                auditTrail);
    }
}
