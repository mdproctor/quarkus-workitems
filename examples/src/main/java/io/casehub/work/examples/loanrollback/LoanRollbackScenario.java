package io.casehub.work.examples.loanrollback;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.casehub.work.api.WorkItem;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.WorkItemPriority;
import io.casehub.work.examples.StepLog;
import io.casehub.work.runtime.service.WorkItemService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

/**
 * Scenario 19 — Multi-Step Loan Application Rollback.
 *
 * <p>
 * Demonstrates application-driven reverse-order compensation of multiple
 * correlated WorkItems. Three sequential loan steps (credit check, property
 * valuation, final approval) are completed, then compensated in reverse
 * dependency order when a regulatory audit discovers outdated scoring data.
 *
 * <p>
 * Uses split transactions ({@code QuarkusTransaction.requiringNew()}) so the
 * intermediate COMPENSATING state is visible between compensation steps.
 *
 * <p>
 * Actors:
 * <ul>
 * <li>{@code loan-system} — creates the three loan process WorkItems</li>
 * <li>{@code credit-analyst} — completes the credit check</li>
 * <li>{@code property-surveyor} — completes the property valuation</li>
 * <li>{@code senior-underwriter} — completes the final approval</li>
 * <li>{@code regulatory-audit} — triggers compensation on all three</li>
 * <li>{@code compliance-officer} — completes each compensating WorkItem</li>
 * </ul>
 *
 * <p>
 * Endpoint: {@code POST /examples/loan-rollback/run}
 */
@Path("/examples/loan-rollback")
@Produces(MediaType.APPLICATION_JSON)
public class LoanRollbackScenario {

    private static final Logger LOG = Logger.getLogger(LoanRollbackScenario.class);
    private static final String SCENARIO_ID = "loan-rollback";
    private static final String LOAN_REF = "loan:L-2026-001";

    @Inject
    WorkItemService workItemService;

    @POST
    @Path("/run")
    public LoanRollbackResponse run() {
        final List<StepLog> steps = new ArrayList<>();

        // --- Phase 1: Forward execution ---

        final UUID creditId = QuarkusTransaction.requiringNew().call(() -> {
            LOG.info("[SCENARIO] Step 1: loan-system creates credit check");
            final WorkItem wi = workItemService.create(WorkItemCreateRequest.builder()
                    .title("Credit check — Loan L-2026-001")
                    .description("Run credit scoring for applicant using current bureau data")
                    .types(List.of("loan", "credit-check"))
                    .priority(WorkItemPriority.HIGH)
                    .candidateGroups("credit-team")
                    .createdBy("loan-system")
                    .callerRef(LOAN_REF + "/credit-check")
                    .build());
            steps.add(new StepLog(1, "loan-system creates credit check", wi.id()));
            return wi.id();
        });

        QuarkusTransaction.requiringNew().run(() -> {
            LOG.info("[SCENARIO] Step 2: credit-analyst claims and completes credit check");
            workItemService.claim(creditId, "credit-analyst");
            workItemService.start(creditId, "credit-analyst");
            workItemService.complete(creditId, "credit-analyst", "Score: 720 — approved", "PASS");
            steps.add(new StepLog(2, "credit-analyst completes credit check (score: 720)", creditId));
        });

        final UUID valuationId = QuarkusTransaction.requiringNew().call(() -> {
            LOG.info("[SCENARIO] Step 3: loan-system creates property valuation");
            final WorkItem wi = workItemService.create(WorkItemCreateRequest.builder()
                    .title("Property valuation — Loan L-2026-001")
                    .description("On-site property assessment for 42 Oak Lane")
                    .types(List.of("loan", "valuation"))
                    .priority(WorkItemPriority.HIGH)
                    .candidateGroups("surveyor-team")
                    .createdBy("loan-system")
                    .callerRef(LOAN_REF + "/valuation")
                    .build());
            steps.add(new StepLog(3, "loan-system creates property valuation", wi.id()));
            return wi.id();
        });

        QuarkusTransaction.requiringNew().run(() -> {
            LOG.info("[SCENARIO] Step 4: property-surveyor claims and completes valuation");
            workItemService.claim(valuationId, "property-surveyor");
            workItemService.start(valuationId, "property-surveyor");
            workItemService.complete(valuationId, "property-surveyor", "Valued at £320,000 — within LTV", "PASS");
            steps.add(new StepLog(4, "property-surveyor completes valuation (£320,000)", valuationId));
        });

        final UUID approvalId = QuarkusTransaction.requiringNew().call(() -> {
            LOG.info("[SCENARIO] Step 5: loan-system creates final approval");
            final WorkItem wi = workItemService.create(WorkItemCreateRequest.builder()
                    .title("Final approval — Loan L-2026-001")
                    .description("Senior underwriter review and final loan approval")
                    .types(List.of("loan", "approval"))
                    .priority(WorkItemPriority.HIGH)
                    .candidateGroups("underwriting-team")
                    .createdBy("loan-system")
                    .callerRef(LOAN_REF + "/approval")
                    .build());
            steps.add(new StepLog(5, "loan-system creates final approval", wi.id()));
            return wi.id();
        });

        QuarkusTransaction.requiringNew().run(() -> {
            LOG.info("[SCENARIO] Step 6: senior-underwriter claims and completes approval");
            workItemService.claim(approvalId, "senior-underwriter");
            workItemService.start(approvalId, "senior-underwriter");
            workItemService.complete(approvalId, "senior-underwriter", "Loan approved — all checks passed", "APPROVED");
            steps.add(new StepLog(6, "senior-underwriter approves the loan", approvalId));
        });

        // --- Phase 2: Compensate in reverse order ---

        final String reason = "Credit check used outdated scoring data — regulatory audit finding RA-2026-031";

        // Compensate approval first (last completed, first reversed)
        final UUID compensatingApprovalId = QuarkusTransaction.requiringNew().call(() -> {
            LOG.info("[SCENARIO] Step 7: regulatory-audit compensates final approval");
            final WorkItem comp = workItemService.compensate(approvalId,
                    WorkItemCreateRequest.builder()
                            .title("Reverse loan approval — L-2026-001")
                            .types(List.of("loan", "compensation"))
                            .priority(WorkItemPriority.URGENT)
                            .candidateGroups("compliance-team")
                            .createdBy("regulatory-audit")
                            .build(),
                    "regulatory-audit", reason);
            steps.add(new StepLog(7, "regulatory-audit compensates final approval", comp.id()));
            return comp.id();
        });

        // Verify intermediate COMPENSATING state
        QuarkusTransaction.requiringNew().run(() -> {
            final WorkItem approvalNow = workItemService.findById(approvalId).orElseThrow();
            LOG.infof("[SCENARIO] Step 8: approval compensationStatus = %s (intermediate)", approvalNow.compensationStatus());
            steps.add(new StepLog(8, "approval is " + approvalNow.compensationStatus() + " (intermediate state)", approvalId));
        });

        QuarkusTransaction.requiringNew().run(() -> {
            LOG.info("[SCENARIO] Step 9: compliance-officer completes compensating approval");
            workItemService.claim(compensatingApprovalId, "compliance-officer");
            workItemService.start(compensatingApprovalId, "compliance-officer");
            workItemService.complete(compensatingApprovalId, "compliance-officer", "Approval reversed", "REVERSED");
            steps.add(new StepLog(9, "compliance-officer completes compensating approval", compensatingApprovalId));
        });

        // Compensate valuation
        final UUID compensatingValuationId = QuarkusTransaction.requiringNew().call(() -> {
            LOG.info("[SCENARIO] Step 10: regulatory-audit compensates valuation");
            final WorkItem comp = workItemService.compensate(valuationId,
                    WorkItemCreateRequest.builder()
                            .title("Invalidate property valuation — L-2026-001")
                            .types(List.of("loan", "compensation"))
                            .priority(WorkItemPriority.URGENT)
                            .candidateGroups("compliance-team")
                            .createdBy("regulatory-audit")
                            .build(),
                    "regulatory-audit", reason);
            steps.add(new StepLog(10, "regulatory-audit compensates valuation", comp.id()));
            return comp.id();
        });

        QuarkusTransaction.requiringNew().run(() -> {
            workItemService.claim(compensatingValuationId, "compliance-officer");
            workItemService.start(compensatingValuationId, "compliance-officer");
            workItemService.complete(compensatingValuationId, "compliance-officer", "Valuation invalidated", "REVERSED");
            steps.add(new StepLog(11, "compliance-officer completes compensating valuation", compensatingValuationId));
        });

        // Compensate credit check (first completed, last reversed)
        final UUID compensatingCreditId = QuarkusTransaction.requiringNew().call(() -> {
            LOG.info("[SCENARIO] Step 12: regulatory-audit compensates credit check");
            final WorkItem comp = workItemService.compensate(creditId,
                    WorkItemCreateRequest.builder()
                            .title("Invalidate credit check — L-2026-001")
                            .types(List.of("loan", "compensation"))
                            .priority(WorkItemPriority.URGENT)
                            .candidateGroups("compliance-team")
                            .createdBy("regulatory-audit")
                            .build(),
                    "regulatory-audit", reason);
            steps.add(new StepLog(12, "regulatory-audit compensates credit check", comp.id()));
            return comp.id();
        });

        QuarkusTransaction.requiringNew().run(() -> {
            workItemService.claim(compensatingCreditId, "compliance-officer");
            workItemService.start(compensatingCreditId, "compliance-officer");
            workItemService.complete(compensatingCreditId, "compliance-officer", "Credit check invalidated — rescore required", "REVERSED");
            steps.add(new StepLog(13, "compliance-officer completes compensating credit check", compensatingCreditId));
        });

        // --- Phase 3: Verify all compensated ---
        return QuarkusTransaction.requiringNew().call(() -> {
            final WorkItem finalCredit = workItemService.findById(creditId).orElseThrow();
            final WorkItem finalValuation = workItemService.findById(valuationId).orElseThrow();
            final WorkItem finalApproval = workItemService.findById(approvalId).orElseThrow();

            steps.add(new StepLog(14, "verify: credit-check=" + finalCredit.compensationStatus()
                    + ", valuation=" + finalValuation.compensationStatus()
                    + ", approval=" + finalApproval.compensationStatus(), null));

            final List<LoanStepSummary> forwardSteps = List.of(
                    new LoanStepSummary(LOAN_REF + "/credit-check", creditId, compensatingCreditId, finalCredit.compensationStatus().name()),
                    new LoanStepSummary(LOAN_REF + "/valuation", valuationId, compensatingValuationId, finalValuation.compensationStatus().name()),
                    new LoanStepSummary(LOAN_REF + "/approval", approvalId, compensatingApprovalId, finalApproval.compensationStatus().name()));

            return new LoanRollbackResponse(
                    SCENARIO_ID,
                    steps,
                    forwardSteps,
                    List.of(
                            new LoanStepSummary(LOAN_REF + "/approval", approvalId, compensatingApprovalId, finalApproval.compensationStatus().name()),
                            new LoanStepSummary(LOAN_REF + "/valuation", valuationId, compensatingValuationId, finalValuation.compensationStatus().name()),
                            new LoanStepSummary(LOAN_REF + "/credit-check", creditId, compensatingCreditId, finalCredit.compensationStatus().name())),
                    "approval → valuation → credit-check");
        });
    }
}
