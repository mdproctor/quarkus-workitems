package io.quarkiverse.work.examples.auditsearch;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.logging.Logger;

import io.quarkiverse.work.examples.StepLog;
import io.quarkiverse.work.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.work.runtime.model.WorkItemPriority;
import io.quarkiverse.work.runtime.repository.AuditEntryStore;
import io.quarkiverse.work.runtime.repository.AuditQuery;
import io.quarkiverse.work.runtime.service.WorkItemService;

/**
 * Scenario 6 — Audit Search: cross-WorkItem audit trail queries.
 *
 * <p>
 * A compliance officer needs to demonstrate to a regulator that every action taken by a
 * specific auditor ({@code auditor-sarah}) can be reconstructed from the audit trail.
 * WorkItems creates three procurement approval requests, has Sarah complete all three
 * (with different resolutions), and then queries the audit store by actor, event type,
 * and category.
 *
 * <p>
 * Actors: {@code procurement-system} (creator), {@code auditor-sarah} (primary reviewer),
 * {@code auditor-james} (secondary reviewer).
 *
 * <p>
 * Endpoint: {@code POST /examples/auditsearch/run}
 */
@Path("/examples/auditsearch")
@Produces(MediaType.APPLICATION_JSON)
public class AuditSearchScenario {

    private static final Logger LOG = Logger.getLogger(AuditSearchScenario.class);

    private static final String SCENARIO_ID = "audit-search";
    private static final String ACTOR_CREATOR = "procurement-system";
    private static final String ACTOR_SARAH = "auditor-sarah";
    private static final String ACTOR_JAMES = "auditor-james";

    @Inject
    WorkItemService workItemService;

    @Inject
    AuditEntryStore auditStore;

    /**
     * Run the audit search scenario end to end and return aggregated query counts.
     *
     * @return scenario response containing steps and audit query results
     */
    @POST
    @Path("/run")
    public AuditSearchResponse run() {
        final List<StepLog> steps = new ArrayList<>();
        final List<UUID> workItemIds = new ArrayList<>();
        final int total = 10;

        // Step 1: create three procurement approval WorkItems
        final String description1 = "procurement-system creates three procurement approval WorkItems";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 1, total, description1);
        final var wi1 = workItemService.create(procurementRequest("Approve vendor contract: Acme Supplies"));
        final var wi2 = workItemService.create(procurementRequest("Approve vendor contract: Beta Tech"));
        final var wi3 = workItemService.create(procurementRequest("Approve vendor contract: Gamma Logistics"));
        workItemIds.add(wi1.id);
        workItemIds.add(wi2.id);
        workItemIds.add(wi3.id);
        steps.add(new StepLog(1, description1, wi1.id));

        // Step 2: auditor-sarah claims all three
        final String description2 = "auditor-sarah claims all three procurement WorkItems";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 2, total, description2);
        workItemService.claim(wi1.id, ACTOR_SARAH);
        workItemService.claim(wi2.id, ACTOR_SARAH);
        workItemService.claim(wi3.id, ACTOR_SARAH);
        steps.add(new StepLog(2, description2, wi1.id));

        // Step 3: auditor-sarah starts all three
        final String description3 = "auditor-sarah starts reviewing all three WorkItems";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 3, total, description3);
        workItemService.start(wi1.id, ACTOR_SARAH);
        workItemService.start(wi2.id, ACTOR_SARAH);
        workItemService.start(wi3.id, ACTOR_SARAH);
        steps.add(new StepLog(3, description3, wi1.id));

        // Step 4: auditor-sarah completes all three with different resolutions
        final String description4 = "auditor-sarah completes all three WorkItems with different resolutions";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 4, total, description4);
        workItemService.complete(wi1.id, ACTOR_SARAH,
                "{\"approved\": true, \"reason\": \"Within budget; vendor approved\"}");
        workItemService.complete(wi2.id, ACTOR_SARAH,
                "{\"approved\": true, \"reason\": \"Preferred supplier; approved under standing agreement\"}");
        workItemService.complete(wi3.id, ACTOR_SARAH,
                "{\"approved\": false, \"reason\": \"Over budget threshold; escalate to CFO\"}");
        steps.add(new StepLog(4, description4, wi1.id));

        // Step 5: create and complete a fourth WorkItem by auditor-james
        final String description5 = "auditor-james completes a different WorkItem (not Sarah's)";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 5, total, description5);
        final var wi4 = workItemService.create(procurementRequest("Approve vendor contract: Delta Freight"));
        workItemIds.add(wi4.id);
        workItemService.claim(wi4.id, ACTOR_JAMES);
        workItemService.start(wi4.id, ACTOR_JAMES);
        workItemService.complete(wi4.id, ACTOR_JAMES,
                "{\"approved\": true, \"reason\": \"Standard freight contract; approved\"}");
        steps.add(new StepLog(5, description5, wi4.id));

        // Step 6: query audit by actorId=auditor-sarah
        final String description6 = "Query audit trail by actorId=auditor-sarah";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 6, total, description6);
        final long sarahActionCount = auditStore.count(
                AuditQuery.builder().actorId(ACTOR_SARAH).build());
        steps.add(new StepLog(6, description6 + " — found " + sarahActionCount + " actions", null));

        // Step 7: query audit by event=COMPLETED
        final String description7 = "Query audit trail by event=COMPLETED";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 7, total, description7);
        final long completionEventCount = auditStore.count(
                AuditQuery.builder().event("COMPLETED").build());
        steps.add(new StepLog(7, description7 + " — found " + completionEventCount + " completions", null));

        // Step 8: query audit by category=procurement
        final String description8 = "Query audit trail by category=procurement";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 8, total, description8);
        final long procurementAuditCount = auditStore.count(
                AuditQuery.builder().category("procurement").build());
        steps.add(new StepLog(8, description8 + " — found " + procurementAuditCount + " entries", null));

        return new AuditSearchResponse(
                SCENARIO_ID,
                steps,
                workItemIds,
                sarahActionCount,
                completionEventCount,
                procurementAuditCount);
    }

    private WorkItemCreateRequest procurementRequest(final String title) {
        return new WorkItemCreateRequest(
                title,
                "Procurement approval required for vendor onboarding",
                "procurement",
                null,
                WorkItemPriority.NORMAL,
                null,
                null,
                null,
                null,
                ACTOR_CREATOR,
                null,
                null,
                null,
                null,
                null,
                null,
                null, null, null);
    }
}
