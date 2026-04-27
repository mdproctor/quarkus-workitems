package io.quarkiverse.work.examples.expense;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.logging.Logger;

import io.quarkiverse.work.examples.ScenarioResponse;
import io.quarkiverse.work.examples.StepLog;
import io.quarkiverse.work.ledger.api.LedgerMapper;
import io.quarkiverse.work.ledger.api.dto.LedgerEntryResponse;
import io.quarkiverse.work.ledger.model.WorkItemLedgerEntry;
import io.quarkiverse.work.ledger.repository.WorkItemLedgerEntryRepository;
import io.quarkiverse.work.runtime.api.AuditEntryResponse;
import io.quarkiverse.work.runtime.model.AuditEntry;
import io.quarkiverse.work.runtime.model.WorkItem;
import io.quarkiverse.work.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.work.runtime.model.WorkItemPriority;
import io.quarkiverse.work.runtime.repository.AuditEntryStore;
import io.quarkiverse.work.runtime.service.WorkItemService;

/**
 * Scenario 1 — Expense Approval.
 *
 * <p>
 * Demonstrates the baseline WorkItem lifecycle: create → claim → start → complete.
 * An employee submits an expense report; a finance manager claims, starts, and approves it.
 *
 * <p>
 * Actors: {@code finance-system} (creator), {@code alice} (claimant/completer).
 * All ledger features active (hash chain, decisionContext).
 *
 * <p>
 * Endpoint: {@code POST /examples/expense/run}
 */
@Path("/examples/expense")
@Produces(MediaType.APPLICATION_JSON)
public class ExpenseApprovalScenario {

    private static final Logger LOG = Logger.getLogger(ExpenseApprovalScenario.class);

    private static final String SCENARIO_ID = "expense-approval";
    private static final String ACTOR_CREATOR = "finance-system";
    private static final String ACTOR_ASSIGNEE = "alice";

    @Inject
    WorkItemService workItemService;

    @Inject
    WorkItemLedgerEntryRepository ledgerRepo;

    @Inject
    AuditEntryStore auditStore;

    /**
     * Run the expense approval scenario from end to end and return the full ledger and audit trail.
     *
     * @return scenario response containing steps, ledger entries, and audit trail
     */
    @POST
    @Path("/run")
    public ScenarioResponse run() {
        final List<StepLog> steps = new ArrayList<>();
        final int total = 4;

        // Step 1: finance-system creates the expense WorkItem
        final String description1 = "Creates expense WorkItem for team offsite Q2";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 1, total, description1);

        final WorkItemCreateRequest request = new WorkItemCreateRequest(
                "Expense report: team offsite Q2",
                "Team offsite expenses for Q2, including travel and accommodation",
                "expense-approval",
                null,
                WorkItemPriority.HIGH,
                ACTOR_ASSIGNEE,
                null,
                null,
                null,
                ACTOR_CREATOR,
                "{\"amount\": 450.00, \"currency\": \"GBP\"}",
                null,
                null,
                null,
                null,
                null,
                null, null, null);

        final WorkItem wi = workItemService.create(request);
        steps.add(new StepLog(1, description1, wi.id));

        // Step 2: alice claims the WorkItem
        final String description2 = "Alice claims the expense WorkItem";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 2, total, description2);
        workItemService.claim(wi.id, ACTOR_ASSIGNEE);
        steps.add(new StepLog(2, description2, wi.id));

        // Step 3: alice starts the WorkItem
        final String description3 = "Alice starts reviewing the expense report";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 3, total, description3);
        workItemService.start(wi.id, ACTOR_ASSIGNEE);
        steps.add(new StepLog(3, description3, wi.id));

        // Step 4: alice completes (approves) the WorkItem
        final String description4 = "Alice completes and approves the expense claim";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 4, total, description4);
        workItemService.complete(
                wi.id,
                ACTOR_ASSIGNEE,
                "{\"approved\": true, \"amount\": 450.00, \"comment\": \"Within policy limits\"}",
                "Expense is within the team policy limit of £500 per person per quarter",
                "EXPENSE-POLICY-v2.1");
        steps.add(new StepLog(4, description4, wi.id));

        // Collect ledger entries
        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(wi.id);
        final List<LedgerEntryResponse> ledgerEntries = entries.stream()
                .map(e -> LedgerMapper.toResponse(e, ledgerRepo.findAttestationsByEntryId(e.id)))
                .toList();

        // Collect audit trail
        final List<AuditEntry> auditEntries = auditStore.findByWorkItemId(wi.id);
        final List<AuditEntryResponse> auditTrail = auditEntries.stream()
                .map(a -> new AuditEntryResponse(a.id, a.event, a.actor, a.detail, a.occurredAt))
                .toList();

        return new ScenarioResponse(SCENARIO_ID, steps, wi.id, ledgerEntries, auditTrail);
    }
}
