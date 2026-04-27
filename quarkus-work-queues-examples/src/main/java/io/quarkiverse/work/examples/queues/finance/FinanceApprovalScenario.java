package io.quarkiverse.work.examples.queues.finance;

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
 * Scenario: Finance Approval Chain.
 *
 * <p>
 * Demonstrates multi-tier approval queues inspired by ServiceNow change management:
 * <ul>
 * <li>Standard finance requests → {@code finance/approval} (standard queue)</li>
 * <li>CRITICAL finance requests → {@code finance/approval} + {@code finance/exec-review}
 * (dual-queue: standard team AND executive oversight)</li>
 * </ul>
 *
 * <p>
 * Three WorkItems are created:
 * <ol>
 * <li>NORMAL expense report → standard approval only → {@link io.quarkiverse.work.queues.event.QueueEventType#ADDED}
 * to Finance Approval Queue</li>
 * <li>HIGH budget reallocation → standard approval only → ADDED to Finance Approval Queue</li>
 * <li>CRITICAL emergency spend → both queues → ADDED to Finance Approval Queue +
 * ADDED to Finance Exec Review Queue</li>
 * </ol>
 *
 * <p>
 * Endpoint: {@code POST /queue-examples/finance/run}
 */
@Path("/queue-examples/finance")
@Produces(MediaType.APPLICATION_JSON)
public class FinanceApprovalScenario {

    private static final Logger LOG = Logger.getLogger(FinanceApprovalScenario.class);

    @Inject
    WorkItemService workItemService;

    @Inject
    WorkItemStore workItemStore;

    @Inject
    QueueEventLog eventLog;

    private void setupFilters() {
        if (WorkItemFilter.count("name", "Finance-A: Standard Approval Queue") > 0)
            return;

        final WorkItemFilter filterA = new WorkItemFilter();
        filterA.name = "Finance-A: Standard Approval Queue";
        filterA.scope = FilterScope.ORG;
        filterA.conditionLanguage = "jexl";
        filterA.conditionExpression = "category == 'finance' && assigneeId == null";
        filterA.actions = WorkItemFilter.serializeActions(List.of(
                FilterAction.applyLabel("finance/approval")));
        filterA.active = true;
        filterA.persist();

        final WorkItemFilter filterB = new WorkItemFilter();
        filterB.name = "Finance-B: Critical Spend to Executive Review";
        filterB.scope = FilterScope.ORG;
        filterB.conditionLanguage = "jexl";
        filterB.conditionExpression = "category == 'finance' && priority == 'CRITICAL'";
        filterB.actions = WorkItemFilter.serializeActions(List.of(
                FilterAction.applyLabel("finance/exec-review")));
        filterB.active = true;
        filterB.persist();
    }

    private void setupQueueViews() {
        if (QueueView.count("name", "Finance Approval Queue") > 0)
            return;

        final QueueView approval = new QueueView();
        approval.name = "Finance Approval Queue";
        approval.labelPattern = "finance/approval";
        approval.scope = FilterScope.ORG;
        approval.persist();

        final QueueView exec = new QueueView();
        exec.name = "Finance Exec Review Queue";
        exec.labelPattern = "finance/exec-review";
        exec.scope = FilterScope.ORG;
        exec.persist();
    }

    /**
     * Run the finance approval chain scenario end to end.
     *
     * @return scenario response with steps, queue events per step, and exec-review queue contents
     */
    @POST
    @Path("/run")
    @Transactional
    public QueueScenarioResponse run() {
        setupFilters();
        setupQueueViews();
        eventLog.clear();
        final List<QueueScenarioStep> steps = new ArrayList<>();

        LOG.info("[FINANCE] Step 1/4: NORMAL expense report → finance/approval only");
        final WorkItem expense = workItemService.create(new WorkItemCreateRequest(
                "Q2 team training budget — approval required",
                "Request to use £2,400 from training budget for team certification renewals.",
                "finance", "budget-request", WorkItemPriority.NORMAL,
                null, "finance-team", null, null, "hr-system",
                "{\"amount\": 2400, \"currency\": \"GBP\", \"category\": \"training\"}",
                null, null, null, null, null, null, null, null));
        steps.add(new QueueScenarioStep(1,
                "NORMAL expense — finance/approval only (standard team queue)",
                expense.id, inferredPaths(expense), manualPaths(expense),
                formatEvents(eventLog.drain())));

        LOG.info("[FINANCE] Step 2/4: HIGH budget reallocation → finance/approval only (HIGH != CRITICAL)");
        final WorkItem realloc = workItemService.create(new WorkItemCreateRequest(
                "Q3 marketing budget reallocation — £15,000 to digital",
                "Propose reallocating £15,000 from events budget to digital marketing for H2.",
                "finance", "budget-reallocation", WorkItemPriority.HIGH,
                null, "finance-team", null, null, "finance-system",
                "{\"amount\": 15000, \"from\": \"events\", \"to\": \"digital\"}",
                null, null, null, null, null, null, null, null));
        steps.add(new QueueScenarioStep(2,
                "HIGH budget reallocation — finance/approval only (CRITICAL threshold not met for exec review)",
                realloc.id, inferredPaths(realloc), manualPaths(realloc),
                formatEvents(eventLog.drain())));

        LOG.info("[FINANCE] Step 3/4: CRITICAL emergency spend → finance/approval + finance/exec-review");
        final WorkItem emergency = workItemService.create(new WorkItemCreateRequest(
                "Emergency cloud spend — incident recovery infrastructure",
                "Incident required provisioning $180,000 of additional cloud capacity.",
                "finance", "emergency-spend", WorkItemPriority.CRITICAL,
                null, "finance-team,executive-team", null, null, "ops-system",
                "{\"amount\": 180000, \"currency\": \"USD\", \"incident_id\": \"INC-9981\"}",
                null, null, null, null, null, null, null, null));
        steps.add(new QueueScenarioStep(3,
                "CRITICAL emergency spend — both finance/approval (standard) AND finance/exec-review (executive oversight)",
                emergency.id, inferredPaths(emergency), manualPaths(emergency),
                formatEvents(eventLog.drain())));

        LOG.info("[FINANCE] Step 4/4: finance/exec-review queue — only CRITICAL item");
        final List<UUID> execQueue = workItemStore.scan(WorkItemQuery.byLabelPattern("finance/exec-review"))
                .stream().map(w -> w.id).toList();
        steps.add(new QueueScenarioStep(4,
                "finance/exec-review queue — contains CRITICAL emergency spend only; NORMAL and HIGH items absent",
                null,
                List.of("finance/exec-review contains " + execQueue.size() + " item(s)"),
                List.of(), List.of()));

        return new QueueScenarioResponse(
                "finance-approval-chain",
                "Multi-tier finance approval: standard queue for all, exec-review queue for CRITICAL only",
                steps, execQueue);
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
