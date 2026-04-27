package io.quarkiverse.work.examples.queues.legal;

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

import io.quarkiverse.work.examples.queues.QueueScenarioResponse;
import io.quarkiverse.work.examples.queues.QueueScenarioStep;
import io.quarkiverse.work.examples.queues.lifecycle.QueueEventLog;
import io.quarkiverse.work.queues.model.FilterAction;
import io.quarkiverse.work.queues.model.FilterScope;
import io.quarkiverse.work.queues.model.QueueView;
import io.quarkiverse.work.queues.model.WorkItemFilter;
import io.quarkiverse.work.runtime.model.LabelPersistence;
import io.quarkiverse.work.runtime.model.WorkItem;
import io.quarkiverse.work.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.work.runtime.model.WorkItemPriority;
import io.quarkiverse.work.runtime.repository.WorkItemQuery;
import io.quarkiverse.work.runtime.repository.WorkItemStore;
import io.quarkiverse.work.runtime.service.WorkItemService;

/**
 * Scenario: Legal Compliance Routing.
 *
 * <p>
 * Demonstrates skill-based routing inspired by Freshdesk/ServiceNow:
 * items categorised as {@code legal} are routed to the legal team queue.
 * HIGH-priority legal items additionally land in an urgent sub-queue.
 * JQ is used for one filter to show language variety.
 *
 * <ul>
 * <li>JEXL filter: {@code category == 'legal'} → {@code legal/review}</li>
 * <li>JQ filter: {@code .category == "legal" and .priority == "HIGH"} → {@code legal/urgent}</li>
 * </ul>
 *
 * <p>
 * Queue events per step:
 * <ol>
 * <li>NORMAL contract → ADDED to Legal Review Queue</li>
 * <li>HIGH NDA dispute → ADDED to Legal Review Queue + ADDED to Legal Urgent Queue</li>
 * </ol>
 *
 * <p>
 * Endpoint: {@code POST /queue-examples/legal/run}
 */
@Path("/queue-examples/legal")
@Produces(MediaType.APPLICATION_JSON)
public class LegalRoutingScenario {

    private static final Logger LOG = Logger.getLogger(LegalRoutingScenario.class);

    @Inject
    WorkItemService workItemService;

    @Inject
    WorkItemStore workItemStore;

    @Inject
    QueueEventLog eventLog;

    private void setupFilters() {
        if (WorkItemFilter.count("name", "Legal-A: Route to Legal Review") > 0)
            return;

        final WorkItemFilter filterA = new WorkItemFilter();
        filterA.name = "Legal-A: Route to Legal Review";
        filterA.scope = FilterScope.ORG;
        filterA.conditionLanguage = "jexl";
        filterA.conditionExpression = "category == 'legal'";
        filterA.actions = WorkItemFilter.serializeActions(List.of(
                FilterAction.applyLabel("legal/review")));
        filterA.active = true;
        filterA.persist();

        final WorkItemFilter filterB = new WorkItemFilter();
        filterB.name = "Legal-B: High Priority to Urgent Queue (JQ)";
        filterB.scope = FilterScope.ORG;
        filterB.conditionLanguage = "jq";
        filterB.conditionExpression = ".category == \"legal\" and .priority == \"HIGH\"";
        filterB.actions = WorkItemFilter.serializeActions(List.of(
                FilterAction.applyLabel("legal/urgent")));
        filterB.active = true;
        filterB.persist();
    }

    private void setupQueueViews() {
        if (QueueView.count("name", "Legal Review Queue") > 0)
            return;

        final QueueView review = new QueueView();
        review.name = "Legal Review Queue";
        review.labelPattern = "legal/review";
        review.scope = FilterScope.ORG;
        review.persist();

        final QueueView urgent = new QueueView();
        urgent.name = "Legal Urgent Queue";
        urgent.labelPattern = "legal/urgent";
        urgent.scope = FilterScope.ORG;
        urgent.persist();
    }

    /**
     * Run the legal compliance routing scenario end to end.
     *
     * @return scenario response with steps, queue events per step, and legal/urgent queue contents
     */
    @POST
    @Path("/run")
    @Transactional
    public QueueScenarioResponse run() {
        setupFilters();
        setupQueueViews();
        eventLog.clear();
        final List<QueueScenarioStep> steps = new ArrayList<>();

        LOG.info("[LEGAL] Step 1/3: NORMAL priority contract review — gets legal/review only");
        final WorkItem contractReview = workItemService.create(new WorkItemCreateRequest(
                "Vendor contract review — Acme Corp SaaS agreement",
                "Review vendor SaaS agreement for GDPR compliance. Non-urgent; renewal date in 6 weeks.",
                "legal", "contract-review", WorkItemPriority.NORMAL,
                null, "legal-team", null, null, "contract-service",
                "{\"vendor\": \"Acme Corp\", \"contract_type\": \"SaaS\", \"renewal_date\": \"2026-06-01\"}",
                null, null, null, null, null, null, null, null));
        steps.add(new QueueScenarioStep(1,
                "NORMAL legal contract review — JEXL filter fires: legal/review; JQ filter (legal+HIGH) does not match",
                contractReview.id, inferredPaths(contractReview), manualPaths(contractReview),
                formatEvents(eventLog.drain())));

        LOG.info("[LEGAL] Step 2/3: HIGH priority NDA dispute — gets legal/review + legal/urgent");
        final WorkItem ndaDispute = workItemService.create(new WorkItemCreateRequest(
                "NDA breach — former employee posted confidential roadmap",
                "Ex-employee posted internal roadmap on LinkedIn. Legal and PR action required immediately.",
                "legal", "nda-breach", WorkItemPriority.HIGH,
                null, "legal-team,executive-team", null, null, "hr-system",
                "{\"employee_id\": \"EMP-4521\", \"disclosure_type\": \"roadmap\", \"channel\": \"LinkedIn\"}",
                null, null, null, null, null, null, null, null));
        steps.add(new QueueScenarioStep(2,
                "HIGH legal NDA dispute — JEXL fires: legal/review; JQ fires: legal/urgent — two filters, two queues",
                ndaDispute.id, inferredPaths(ndaDispute), manualPaths(ndaDispute),
                formatEvents(eventLog.drain())));

        LOG.info("[LEGAL] Step 3/3: legal/urgent queue contains only the NDA dispute");
        final List<UUID> urgentQueue = workItemStore.scan(WorkItemQuery.byLabelPattern("legal/urgent"))
                .stream().map(w -> w.id).toList();
        steps.add(new QueueScenarioStep(3,
                "legal/urgent queue — contains HIGH priority items only; NORMAL contract review is absent",
                null,
                List.of("legal/urgent contains " + urgentQueue.size() + " item(s)"),
                List.of(), List.of()));

        return new QueueScenarioResponse(
                "legal-compliance-routing",
                "Skill-based legal routing: JEXL + JQ filters, all legal items to review queue, HIGH items to urgent sub-queue",
                steps, urgentQueue);
    }

    private List<String> inferredPaths(final WorkItem wi) {
        return wi.labels.stream().filter(l -> l.persistence == LabelPersistence.INFERRED)
                .map(l -> l.path).toList();
    }

    private List<String> manualPaths(final WorkItem wi) {
        return wi.labels.stream().filter(l -> l.persistence == LabelPersistence.MANUAL)
                .map(l -> l.path).toList();
    }

    private List<String> formatEvents(final List<QueueEventLog.Entry> entries) {
        return entries.stream()
                .map(e -> e.eventType().name() + " to " + e.queueName())
                .toList();
    }
}
