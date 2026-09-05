package io.casehub.work.examples.compensation;

import java.util.ArrayList;
import java.util.List;

import io.casehub.work.api.WorkItem;
import io.casehub.work.api.AuditEntryResponse;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.WorkItemPriority;
import io.casehub.work.examples.StepLog;
import io.casehub.work.runtime.model.AuditEntry;
import io.casehub.work.runtime.repository.AuditEntryStore;
import io.casehub.work.runtime.service.WorkItemService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

/**
 * Scenario 18 — Expense Approval Reversal.
 *
 * <p>
 * Demonstrates the full compensation lifecycle: a completed vendor payment
 * approval is compensated when internal audit discovers the invoice was for a
 * cancelled project. A compliance officer (different actor) handles the reversal.
 *
 * <p>
 * Actors:
 * <ul>
 * <li>{@code finance-analyst} — creates the payment approval request</li>
 * <li>{@code senior-finance-officer} — claims and approves the payment</li>
 * <li>{@code internal-audit} — triggers compensation after discovering the error</li>
 * <li>{@code compliance-officer} — claims and completes the compensating WorkItem</li>
 * </ul>
 *
 * <p>
 * Endpoint: {@code POST /examples/compensation/run}
 */
@Path("/examples/compensation")
@Produces(MediaType.APPLICATION_JSON)
public class CompensationScenario {

    private static final Logger LOG = Logger.getLogger(CompensationScenario.class);
    private static final String SCENARIO_ID = "expense-compensation";

    @Inject
    WorkItemService workItemService;

    @Inject
    AuditEntryStore auditStore;

    @POST
    @Path("/run")
    @Transactional
    public CompensationResponse run() {
        final List<StepLog> steps = new ArrayList<>();
        final int total = 8;

        LOG.infof("[SCENARIO] Step 1/%d: finance-analyst creates $50K vendor payment approval", total);
        final WorkItem original = workItemService.create(WorkItemCreateRequest.builder()
                .title("Vendor payment approval: $50,000 — Acme Consulting")
                .description("Invoice INV-2026-847 for cancelled Project Atlas consulting services")
                .types(List.of("finance", "approval"))
                .priority(WorkItemPriority.HIGH)
                .candidateGroups("finance-team")
                .createdBy("finance-analyst")
                .payload("{\"vendor\": \"Acme Consulting\", \"amount\": 50000, \"invoice\": \"INV-2026-847\"}")
                .build());
        steps.add(new StepLog(1, "finance-analyst creates $50K vendor payment approval", original.id()));

        LOG.infof("[SCENARIO] Step 2/%d: senior-finance-officer claims the approval", total);
        workItemService.claim(original.id(), "senior-finance-officer");
        steps.add(new StepLog(2, "senior-finance-officer claims the approval", original.id()));

        LOG.infof("[SCENARIO] Step 3/%d: senior-finance-officer starts reviewing the invoice", total);
        workItemService.start(original.id(), "senior-finance-officer");
        steps.add(new StepLog(3, "senior-finance-officer starts reviewing the invoice", original.id()));

        LOG.infof("[SCENARIO] Step 4/%d: senior-finance-officer approves the payment", total);
        workItemService.complete(original.id(), "senior-finance-officer",
                "Approved — vendor invoice verified against PO-2026-312", "APPROVED");
        steps.add(new StepLog(4, "senior-finance-officer approves the payment", original.id()));

        LOG.infof("[SCENARIO] Step 5/%d: internal-audit discovers Project Atlas was cancelled — triggers compensation", total);
        final String reason = "Invoice INV-2026-847 is for cancelled project Atlas — payment must be reversed";
        final WorkItem compensating = workItemService.compensate(
                original.id(),
                WorkItemCreateRequest.builder()
                        .title("Reverse payment approval: INV-2026-847 — Project Atlas cancelled")
                        .description("Original approval was for a cancelled project. Reverse the payment authorisation and notify the vendor.")
                        .types(List.of("finance", "compensation"))
                        .priority(WorkItemPriority.URGENT)
                        .candidateGroups("compliance-team")
                        .createdBy("internal-audit")
                        .payload("{\"originalInvoice\": \"INV-2026-847\", \"reversalReason\": \"Project Atlas cancelled 2026-08-15\"}")
                        .build(),
                "internal-audit",
                reason);
        steps.add(new StepLog(5, "internal-audit triggers compensation — compensating WorkItem created", compensating.id()));

        LOG.infof("[SCENARIO] Step 6/%d: compliance-officer claims the compensation task", total);
        workItemService.claim(compensating.id(), "compliance-officer");
        steps.add(new StepLog(6, "compliance-officer claims the compensation task", compensating.id()));

        workItemService.start(compensating.id(), "compliance-officer");

        LOG.infof("[SCENARIO] Step 7/%d: compliance-officer reverses the payment authorisation", total);
        workItemService.complete(compensating.id(), "compliance-officer",
                "Payment reversal authorised — vendor notified, ref REV-2026-019", "REVERSED");
        steps.add(new StepLog(7, "compliance-officer reverses the payment authorisation", compensating.id()));

        LOG.infof("[SCENARIO] Step 8/%d: verify original WorkItem is now COMPENSATED", total);
        final WorkItem finalOriginal = workItemService.findById(original.id()).orElseThrow();
        steps.add(new StepLog(8, "original WorkItem compensationStatus: " + finalOriginal.compensationStatus(), original.id()));

        final List<AuditEntryResponse> originalAudit = auditStore.findByWorkItemId(original.id()).stream()
                .map(a -> new AuditEntryResponse(a.id, a.event, a.actor, a.detail, a.occurredAt))
                .toList();
        final List<AuditEntryResponse> compensatingAudit = auditStore.findByWorkItemId(compensating.id()).stream()
                .map(a -> new AuditEntryResponse(a.id, a.event, a.actor, a.detail, a.occurredAt))
                .toList();

        return new CompensationResponse(
                SCENARIO_ID,
                steps,
                original.id(),
                compensating.id(),
                finalOriginal.compensationStatus().name(),
                compensating.compensatesWorkItemId().toString(),
                "internal-audit",
                reason,
                originalAudit,
                compensatingAudit);
    }
}
