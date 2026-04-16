package io.quarkiverse.workitems.examples.queues.triage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.logging.Logger;

import io.quarkiverse.workitems.examples.queues.QueueScenarioResponse;
import io.quarkiverse.workitems.examples.queues.QueueScenarioStep;
import io.quarkiverse.workitems.queues.model.FilterAction;
import io.quarkiverse.workitems.queues.model.FilterScope;
import io.quarkiverse.workitems.queues.model.WorkItemFilter;
import io.quarkiverse.workitems.runtime.model.LabelPersistence;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.workitems.runtime.model.WorkItemPriority;
import io.quarkiverse.workitems.runtime.repository.WorkItemRepository;
import io.quarkiverse.workitems.runtime.service.WorkItemService;

/**
 * Scenario: Support Triage Cascade.
 *
 * <p>
 * Demonstrates 3-step label propagation inspired by Desk.com/Zendesk business rules:
 * <ol>
 * <li>Filter A: {@code priority == 'CRITICAL'} → labels {@code sla/critical} and {@code queue/fast-track}</li>
 * <li>Filter B: {@code priority == 'HIGH' && assigneeId == null} → label {@code intake/triage}</li>
 * <li>Filter C: {@code labels.contains('intake/triage')} → label {@code team/support-lead}
 * (propagation — fires because Filter B applied its label)</li>
 * </ol>
 *
 * <p>
 * A CRITICAL ticket goes to the fast-track queue. A HIGH unassigned ticket goes to triage
 * AND triggers a second-pass rule that flags it for support lead review. Once the HIGH
 * ticket is claimed, it leaves both queues automatically on the next evaluation.
 *
 * <p>
 * Endpoint: {@code POST /queue-examples/triage/run}
 */
@Path("/queue-examples/triage")
@Produces(MediaType.APPLICATION_JSON)
public class SupportTriageScenario {

    private static final Logger LOG = Logger.getLogger(SupportTriageScenario.class);

    @Inject
    WorkItemService workItemService;

    @Inject
    WorkItemRepository workItemRepo;

    private void setupFilters() {
        // Only create filters once (idempotent on scenario name)
        if (WorkItemFilter.count("name", "Triage-A: Critical SLA") > 0)
            return;

        // Filter A: CRITICAL priority → fast-track SLA queue
        final WorkItemFilter filterA = new WorkItemFilter();
        filterA.name = "Triage-A: Critical SLA";
        filterA.scope = FilterScope.ORG;
        filterA.conditionLanguage = "jexl";
        filterA.conditionExpression = "priority == 'CRITICAL'";
        filterA.actions = WorkItemFilter.serializeActions(List.of(
                FilterAction.applyLabel("sla/critical"),
                FilterAction.applyLabel("queue/fast-track")));
        filterA.active = true;
        filterA.persist();

        // Filter B: HIGH + unassigned → intake triage queue
        final WorkItemFilter filterB = new WorkItemFilter();
        filterB.name = "Triage-B: High Unassigned to Intake";
        filterB.scope = FilterScope.ORG;
        filterB.conditionLanguage = "jexl";
        filterB.conditionExpression = "priority == 'HIGH' && assigneeId == null";
        filterB.actions = WorkItemFilter.serializeActions(List.of(
                FilterAction.applyLabel("intake/triage")));
        filterB.active = true;
        filterB.persist();

        // Filter C: has intake/triage label → flag for support lead review (cascade)
        final WorkItemFilter filterC = new WorkItemFilter();
        filterC.name = "Triage-C: Intake Triage → Support Lead";
        filterC.scope = FilterScope.ORG;
        filterC.conditionLanguage = "jexl";
        filterC.conditionExpression = "labels.contains('intake/triage')";
        filterC.actions = WorkItemFilter.serializeActions(List.of(
                FilterAction.applyLabel("team/support-lead")));
        filterC.active = true;
        filterC.persist();
    }

    /**
     * Run the support triage cascade scenario end to end.
     *
     * @return scenario response with steps and fast-track queue contents
     */
    @POST
    @Path("/run")
    @Transactional
    public QueueScenarioResponse run() {
        setupFilters();
        final List<QueueScenarioStep> steps = new ArrayList<>();

        LOG.info("[TRIAGE] Step 1/4: Creating CRITICAL ticket — should get sla/critical + queue/fast-track");
        final WorkItem critical = workItemService.create(new WorkItemCreateRequest(
                "Production database unreachable — all services down",
                "Database cluster is not responding. All customer-facing APIs returning 503.",
                "infrastructure",
                null, WorkItemPriority.CRITICAL,
                null, "ops-team", null, null,
                "incident-detector", "{\"affected_services\": 12, \"error\": \"Connection refused\"}",
                null, null, null, null));

        steps.add(new QueueScenarioStep(1,
                "CRITICAL production incident created — filter A fires: sla/critical + queue/fast-track",
                critical.id,
                inferredPaths(critical),
                manualPaths(critical)));

        LOG.info("[TRIAGE] Step 2/4: Creating HIGH unassigned ticket — should get intake/triage + team/support-lead (cascade)");
        final WorkItem high = workItemService.create(new WorkItemCreateRequest(
                "Login flow broken for EU region customers",
                "Multiple reports of authentication failures for users in EU region. Appears intermittent.",
                "authentication",
                null, WorkItemPriority.HIGH,
                null, "support-tier1", null, null,
                "support-portal", "{\"region\": \"EU\", \"affected_users\": 47}",
                null, null, null, null));

        steps.add(new QueueScenarioStep(2,
                "HIGH unassigned ticket — filter B fires: intake/triage; filter C cascades: team/support-lead",
                high.id,
                inferredPaths(high),
                manualPaths(high)));

        LOG.info("[TRIAGE] Step 3/4: Claiming the HIGH ticket — intake/triage should disappear on re-evaluation");
        workItemService.claim(high.id, "senior-support-agent");
        final WorkItem highAfterClaim = workItemRepo.findById(high.id).orElseThrow();

        steps.add(new QueueScenarioStep(3,
                "HIGH ticket claimed by senior-support-agent — re-evaluation fires: assigneeId != null so intake/triage and team/support-lead labels removed",
                highAfterClaim.id,
                inferredPaths(highAfterClaim),
                manualPaths(highAfterClaim)));

        LOG.info("[TRIAGE] Step 4/4: Listing queue/fast-track contents — should contain CRITICAL ticket only");
        final List<UUID> fastTrackQueue = workItemRepo.findByLabelPattern("queue/fast-track")
                .stream().map(w -> w.id).toList();

        steps.add(new QueueScenarioStep(4,
                "queue/fast-track queue contents — contains CRITICAL ticket; HIGH ticket left when claimed",
                null, List.of("queue/fast-track contains " + fastTrackQueue.size() + " item(s)"),
                List.of()));

        return new QueueScenarioResponse(
                "support-triage-cascade",
                "Desk.com-inspired 3-step cascade: CRITICAL→fast-track, HIGH+unassigned→triage→lead-review, claimed item leaves queue",
                steps, fastTrackQueue);
    }

    private List<String> inferredPaths(final WorkItem wi) {
        return wi.labels.stream()
                .filter(l -> l.persistence == LabelPersistence.INFERRED)
                .map(l -> l.path).toList();
    }

    private List<String> manualPaths(final WorkItem wi) {
        return wi.labels.stream()
                .filter(l -> l.persistence == LabelPersistence.MANUAL)
                .map(l -> l.path).toList();
    }
}
