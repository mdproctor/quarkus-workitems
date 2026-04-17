package io.quarkiverse.workitems.examples.credit;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.logging.Logger;

import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.AttestationVerdict;
import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.supplement.ProvenanceSupplement;
import io.quarkiverse.workitems.examples.ScenarioResponse;
import io.quarkiverse.workitems.examples.StepLog;
import io.quarkiverse.workitems.ledger.api.LedgerMapper;
import io.quarkiverse.workitems.ledger.api.dto.LedgerEntryResponse;
import io.quarkiverse.workitems.ledger.model.WorkItemLedgerEntry;
import io.quarkiverse.workitems.ledger.repository.WorkItemLedgerEntryRepository;
import io.quarkiverse.workitems.runtime.api.AuditEntryResponse;
import io.quarkiverse.workitems.runtime.model.AuditEntry;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.workitems.runtime.model.WorkItemPriority;
import io.quarkiverse.workitems.runtime.repository.AuditEntryStore;
import io.quarkiverse.workitems.runtime.service.WorkItemService;

/**
 * Scenario 2 — Regulated Credit Decision.
 *
 * <p>
 * Demonstrates the full compliance feature set: suspend/resume, delegation with causal
 * linkage ({@code causedByEntryId}), completion with rationale and policy reference
 * (GDPR Article 22 / EU AI Act Article 12), and a peer attestation (dual-control).
 *
 * <p>
 * Actors:
 * <ul>
 * <li>{@code credit-scoring-system} — creates the loan review WorkItem (provenance from credit-engine)</li>
 * <li>{@code officer-alice} — claims, starts, suspends (awaiting payslips), resumes, and delegates</li>
 * <li>{@code supervisor-bob} — claims, starts, and completes with rationale and policy reference</li>
 * <li>{@code compliance-carol} — adds a SOUND peer attestation (dual-control)</li>
 * </ul>
 *
 * <p>
 * Endpoint: {@code POST /examples/credit/run}
 */
@Path("/examples/credit")
@Produces(MediaType.APPLICATION_JSON)
public class CreditDecisionScenario {

    private static final Logger LOG = Logger.getLogger(CreditDecisionScenario.class);

    private static final String SCENARIO_ID = "credit-decision";
    private static final String ACTOR_CREATOR = "credit-scoring-system";
    private static final String ACTOR_ALICE = "officer-alice";
    private static final String ACTOR_BOB = "supervisor-bob";
    private static final String ACTOR_CAROL = "compliance-carol";

    @Inject
    WorkItemService workItemService;

    @Inject
    WorkItemLedgerEntryRepository ledgerRepo;

    @Inject
    AuditEntryStore auditStore;

    /**
     * Run the regulated credit decision scenario from end to end and return the full ledger and audit trail.
     *
     * @return scenario response containing steps, ledger entries, and audit trail
     */
    @POST
    @Path("/run")
    @Transactional
    public ScenarioResponse run() {
        final List<StepLog> steps = new ArrayList<>();
        final int total = 9;

        // Step 1: credit-scoring-system creates the loan review WorkItem
        final String description1 = "credit-scoring-system creates loan review WorkItem for LOAN-8821";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 1, total, description1);

        final WorkItemCreateRequest request = new WorkItemCreateRequest(
                "Loan review: LOAN-8821",
                "Regulated credit decision for personal loan application LOAN-8821",
                "credit-decision",
                null,
                WorkItemPriority.HIGH,
                null,
                null,
                null,
                null,
                ACTOR_CREATOR,
                "{\"loanId\": \"LOAN-8821\", \"amount\": 25000, \"currency\": \"GBP\"}",
                null,
                null,
                null,
                null);

        final WorkItem wi = workItemService.create(request);
        steps.add(new StepLog(1, description1, wi.id));

        // Set provenance on entry 1 (the creation ledger entry)
        final WorkItemLedgerEntry creationEntry = ledgerRepo.findByWorkItemId(wi.id).stream()
                .filter(e -> e.sequenceNumber == 1)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No creation ledger entry found"));
        final var creationProvenance = new ProvenanceSupplement();
        creationProvenance.sourceEntityId = "LOAN-8821";
        creationProvenance.sourceEntityType = "LoanApplication";
        creationProvenance.sourceEntitySystem = "credit-engine";
        creationEntry.attach(creationProvenance);

        // Step 2: officer-alice claims the WorkItem
        final String description2 = "officer-alice claims the loan review WorkItem";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 2, total, description2);
        workItemService.claim(wi.id, ACTOR_ALICE);
        steps.add(new StepLog(2, description2, wi.id));

        // Step 3: officer-alice starts the WorkItem
        final String description3 = "officer-alice starts reviewing the loan application";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 3, total, description3);
        workItemService.start(wi.id, ACTOR_ALICE);
        steps.add(new StepLog(3, description3, wi.id));

        // Step 4: officer-alice suspends — awaiting payslip documents
        final String description4 = "officer-alice suspends — Awaiting payslip documents";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 4, total, description4);
        workItemService.suspend(wi.id, ACTOR_ALICE, "Awaiting payslip documents");
        steps.add(new StepLog(4, description4, wi.id));

        // Step 5: officer-alice resumes after payslips received
        final String description5 = "officer-alice resumes after payslips received";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 5, total, description5);
        workItemService.resume(wi.id, ACTOR_ALICE);
        steps.add(new StepLog(5, description5, wi.id));

        // Capture the resume entry id before delegating
        final WorkItemLedgerEntry resumeEntry = ledgerRepo.findLatestByWorkItemId(wi.id)
                .orElseThrow(() -> new IllegalStateException("No resume ledger entry found"));

        // Step 6: officer-alice delegates to supervisor-bob (complex case)
        final String description6 = "officer-alice delegates to supervisor-bob for final decision";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 6, total, description6);
        workItemService.delegate(wi.id, ACTOR_ALICE, ACTOR_BOB);
        steps.add(new StepLog(6, description6, wi.id));

        // Set causedByEntryId on the delegation entry — linking it to the resume entry
        final WorkItemLedgerEntry delegationEntry = ledgerRepo.findLatestByWorkItemId(wi.id)
                .orElseThrow(() -> new IllegalStateException("No delegation ledger entry found"));
        // causedByEntryId linking deferred — ObservabilitySupplement not yet in quarkus-ledger

        // Step 7: supervisor-bob claims the delegated WorkItem
        final String description7 = "supervisor-bob claims the delegated WorkItem";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 7, total, description7);
        workItemService.claim(wi.id, ACTOR_BOB);
        steps.add(new StepLog(7, description7, wi.id));

        // Step 8: supervisor-bob starts the WorkItem
        final String description8 = "supervisor-bob starts the final credit review";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 8, total, description8);
        workItemService.start(wi.id, ACTOR_BOB);
        steps.add(new StepLog(8, description8, wi.id));

        // Step 9: supervisor-bob completes with rationale and policy reference (GDPR Art. 22)
        final String description9 = "supervisor-bob completes with approval decision and policy reference";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 9, total, description9);
        workItemService.complete(
                wi.id,
                ACTOR_BOB,
                "{\"decision\": \"APPROVED\", \"conditions\": \"Income verified; standard terms apply\"}",
                "Income verified against payslips",
                "credit-policy-v2.1");
        steps.add(new StepLog(9, description9, wi.id));

        // Add peer attestation from compliance-carol (dual-control)
        final WorkItemLedgerEntry completionEntry = ledgerRepo.findLatestByWorkItemId(wi.id)
                .orElseThrow(() -> new IllegalStateException("No completion ledger entry found"));

        final LedgerAttestation attestation = new LedgerAttestation();
        attestation.ledgerEntryId = completionEntry.id;
        attestation.subjectId = wi.id;
        attestation.attestorId = ACTOR_CAROL;
        attestation.attestorType = ActorType.HUMAN;
        attestation.verdict = AttestationVerdict.SOUND;
        attestation.evidence = "Dual-control review passed";
        attestation.confidence = 0.95;
        ledgerRepo.saveAttestation(attestation);

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
