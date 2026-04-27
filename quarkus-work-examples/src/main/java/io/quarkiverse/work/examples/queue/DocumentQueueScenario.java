package io.quarkiverse.work.examples.queue;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.logging.Logger;

import io.quarkiverse.ledger.runtime.model.ActorTrustScore;
import io.quarkiverse.ledger.runtime.repository.ActorTrustScoreRepository;
import io.quarkiverse.ledger.runtime.service.TrustScoreJob;
import io.quarkiverse.work.examples.QueueScenarioResponse;
import io.quarkiverse.work.examples.StepLog;
import io.quarkiverse.work.ledger.api.LedgerMapper;
import io.quarkiverse.work.ledger.api.dto.ActorTrustScoreResponse;
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
 * Scenario 4 — Document Review Queue.
 *
 * <p>
 * Demonstrates group-based work queues with candidate groups, claim-and-release
 * patterns, and EigenTrust-based accountability scoring. Three document review
 * WorkItems are posted to the {@code doc-reviewers} candidate group. reviewer-alice
 * claims and releases WI-1; reviewer-bob claims all three and completes them;
 * reviewer-alice completes the one she originally released. Trust scores are computed
 * via {@link TrustScoreJob#runComputation()} and returned, demonstrating that bob
 * (more completions, no releases) scores higher than alice.
 *
 * <p>
 * Actors:
 * <ul>
 * <li>{@code system:document-system} — creates all three WorkItems</li>
 * <li>{@code reviewer-alice} — claims WI-1, releases it, later claims and completes WI-3</li>
 * <li>{@code reviewer-bob} — claims and completes WI-1, WI-2, and WI-3</li>
 * </ul>
 *
 * <p>
 * WI-1 lifecycle: create → claim(alice) → release(alice) → claim(bob) → start(bob) → complete(bob) = 6 entries<br>
 * WI-2 lifecycle: create → claim(bob) → start(bob) → complete(bob) = 4 entries<br>
 * WI-3 lifecycle: create → claim(alice) → start(alice) → complete(alice) = 4 entries<br>
 * Total: 14 ledger entries.
 *
 * <p>
 * Endpoint: {@code POST /examples/queue/run}
 */
@Path("/examples/queue")
@Produces(MediaType.APPLICATION_JSON)
public class DocumentQueueScenario {

    private static final Logger LOG = Logger.getLogger(DocumentQueueScenario.class);

    private static final String SCENARIO_ID = "document-queue";
    private static final String ACTOR_CREATOR = "system:document-system";
    private static final String ACTOR_ALICE = "reviewer-alice";
    private static final String ACTOR_BOB = "reviewer-bob";
    private static final String CANDIDATE_GROUP = "doc-reviewers";

    @Inject
    WorkItemService workItemService;

    @Inject
    WorkItemLedgerEntryRepository ledgerRepo;

    @Inject
    AuditEntryStore auditStore;

    @Inject
    TrustScoreJob trustScoreJob;

    @Inject
    ActorTrustScoreRepository trustScoreRepo;

    /**
     * Run the document review queue scenario end to end and return the full ledger, audit
     * trail, and computed trust scores for both reviewers.
     *
     * @return scenario response containing steps, work item IDs, ledger entries, audit trail,
     *         and trust scores for reviewer-bob and reviewer-alice
     */
    @POST
    @Path("/run")
    @Transactional
    public QueueScenarioResponse run() {
        final List<StepLog> steps = new ArrayList<>();
        final int total = 14;

        // ----------------------------------------------------------------
        // Create WI-1: contract-review-001
        // ----------------------------------------------------------------
        final String desc1 = "system:document-system creates document review WorkItem WI-1 (contract-review-001)";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 1, total, desc1);
        final WorkItemCreateRequest req1 = new WorkItemCreateRequest(
                "Contract review: contract-review-001",
                "Review and approve vendor contract for Q2 2026",
                "document-review",
                null,
                WorkItemPriority.NORMAL,
                null,
                CANDIDATE_GROUP,
                null,
                null,
                ACTOR_CREATOR,
                "{\"documentId\": \"contract-review-001\", \"documentType\": \"vendor-contract\"}",
                null,
                null,
                null,
                null,
                null,
                null, null, null);
        final WorkItem wi1 = workItemService.create(req1);
        steps.add(new StepLog(1, desc1, wi1.id));

        // ----------------------------------------------------------------
        // Create WI-2: policy-review-007
        // ----------------------------------------------------------------
        final String desc2 = "system:document-system creates document review WorkItem WI-2 (policy-review-007)";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 2, total, desc2);
        final WorkItemCreateRequest req2 = new WorkItemCreateRequest(
                "Policy review: policy-review-007",
                "Review and approve updated data retention policy",
                "document-review",
                null,
                WorkItemPriority.NORMAL,
                null,
                CANDIDATE_GROUP,
                null,
                null,
                ACTOR_CREATOR,
                "{\"documentId\": \"policy-review-007\", \"documentType\": \"data-policy\"}",
                null,
                null,
                null,
                null,
                null,
                null, null, null);
        final WorkItem wi2 = workItemService.create(req2);
        steps.add(new StepLog(2, desc2, wi2.id));

        // ----------------------------------------------------------------
        // Create WI-3: sla-review-003
        // ----------------------------------------------------------------
        final String desc3 = "system:document-system creates document review WorkItem WI-3 (sla-review-003)";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 3, total, desc3);
        final WorkItemCreateRequest req3 = new WorkItemCreateRequest(
                "SLA review: sla-review-003",
                "Review and approve service level agreement for cloud provider",
                "document-review",
                null,
                WorkItemPriority.NORMAL,
                null,
                CANDIDATE_GROUP,
                null,
                null,
                ACTOR_CREATOR,
                "{\"documentId\": \"sla-review-003\", \"documentType\": \"sla\"}",
                null,
                null,
                null,
                null,
                null,
                null, null, null);
        final WorkItem wi3 = workItemService.create(req3);
        steps.add(new StepLog(3, desc3, wi3.id));

        // ----------------------------------------------------------------
        // WI-1: reviewer-alice claims then releases
        // ----------------------------------------------------------------
        final String desc4 = "reviewer-alice claims WI-1 (contract-review-001)";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 4, total, desc4);
        workItemService.claim(wi1.id, ACTOR_ALICE);
        steps.add(new StepLog(4, desc4, wi1.id));

        final String desc5 = "reviewer-alice releases WI-1 — unable to review at this time";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 5, total, desc5);
        workItemService.release(wi1.id, ACTOR_ALICE);
        steps.add(new StepLog(5, desc5, wi1.id));

        // ----------------------------------------------------------------
        // WI-1: reviewer-bob claims, starts, completes
        // ----------------------------------------------------------------
        final String desc6 = "reviewer-bob claims WI-1 from the doc-reviewers queue";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 6, total, desc6);
        workItemService.claim(wi1.id, ACTOR_BOB);
        steps.add(new StepLog(6, desc6, wi1.id));

        final String desc7 = "reviewer-bob starts reviewing WI-1 (contract-review-001)";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 7, total, desc7);
        workItemService.start(wi1.id, ACTOR_BOB);
        steps.add(new StepLog(7, desc7, wi1.id));

        final String desc8 = "reviewer-bob completes WI-1 — contract approved";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 8, total, desc8);
        workItemService.complete(wi1.id, ACTOR_BOB, "{\"decision\": \"APPROVED\", \"documentId\": \"contract-review-001\"}");
        steps.add(new StepLog(8, desc8, wi1.id));

        // ----------------------------------------------------------------
        // WI-2: reviewer-bob claims, starts, completes
        // ----------------------------------------------------------------
        final String desc9 = "reviewer-bob claims WI-2 (policy-review-007)";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 9, total, desc9);
        workItemService.claim(wi2.id, ACTOR_BOB);
        steps.add(new StepLog(9, desc9, wi2.id));

        final String desc10 = "reviewer-bob starts reviewing WI-2 (policy-review-007)";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 10, total, desc10);
        workItemService.start(wi2.id, ACTOR_BOB);
        steps.add(new StepLog(10, desc10, wi2.id));

        final String desc11 = "reviewer-bob completes WI-2 — policy approved with minor amendments";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 11, total, desc11);
        workItemService.complete(wi2.id, ACTOR_BOB,
                "{\"decision\": \"APPROVED_WITH_AMENDMENTS\", \"documentId\": \"policy-review-007\"}");
        steps.add(new StepLog(11, desc11, wi2.id));

        // ----------------------------------------------------------------
        // WI-3: reviewer-alice claims, starts, completes
        // ----------------------------------------------------------------
        final String desc12 = "reviewer-alice claims WI-3 (sla-review-003)";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 12, total, desc12);
        workItemService.claim(wi3.id, ACTOR_ALICE);
        steps.add(new StepLog(12, desc12, wi3.id));

        final String desc13 = "reviewer-alice starts reviewing WI-3 (sla-review-003)";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 13, total, desc13);
        workItemService.start(wi3.id, ACTOR_ALICE);
        steps.add(new StepLog(13, desc13, wi3.id));

        final String desc14 = "reviewer-alice completes WI-3 — SLA approved";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 14, total, desc14);
        workItemService.complete(wi3.id, ACTOR_ALICE, "{\"decision\": \"APPROVED\", \"documentId\": \"sla-review-003\"}");
        steps.add(new StepLog(14, desc14, wi3.id));

        // ----------------------------------------------------------------
        // Compute trust scores
        // ----------------------------------------------------------------
        trustScoreJob.runComputation();

        // ----------------------------------------------------------------
        // Collect ledger entries across all 3 WorkItems
        // ----------------------------------------------------------------
        final List<LedgerEntryResponse> allLedgerEntries = new ArrayList<>();
        for (final WorkItem wi : List.of(wi1, wi2, wi3)) {
            final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(wi.id);
            entries.stream()
                    .map(e -> LedgerMapper.toResponse(e, ledgerRepo.findAttestationsByEntryId(e.id)))
                    .forEach(allLedgerEntries::add);
        }

        // ----------------------------------------------------------------
        // Collect audit entries across all 3 WorkItems
        // ----------------------------------------------------------------
        final List<AuditEntryResponse> allAuditEntries = new ArrayList<>();
        for (final WorkItem wi : List.of(wi1, wi2, wi3)) {
            final List<AuditEntry> auditEntries = auditStore.findByWorkItemId(wi.id);
            auditEntries.stream()
                    .map(a -> new AuditEntryResponse(a.id, a.event, a.actor, a.detail, a.occurredAt))
                    .forEach(allAuditEntries::add);
        }

        // ----------------------------------------------------------------
        // Fetch trust scores
        // ----------------------------------------------------------------
        final ActorTrustScoreResponse bobTrust = trustScoreRepo.findByActorId(ACTOR_BOB)
                .map(this::toResponse)
                .orElseThrow(() -> new IllegalStateException("Trust score not found for " + ACTOR_BOB));

        final ActorTrustScoreResponse aliceTrust = trustScoreRepo.findByActorId(ACTOR_ALICE)
                .map(this::toResponse)
                .orElseThrow(() -> new IllegalStateException("Trust score not found for " + ACTOR_ALICE));

        return new QueueScenarioResponse(
                SCENARIO_ID,
                steps,
                List.of(wi1.id, wi2.id, wi3.id),
                allLedgerEntries,
                allAuditEntries,
                bobTrust,
                aliceTrust);
    }

    private ActorTrustScoreResponse toResponse(final ActorTrustScore score) {
        return new ActorTrustScoreResponse(
                score.actorId,
                score.actorType,
                score.trustScore,
                score.decisionCount,
                score.overturnedCount,
                score.attestationPositive,
                score.attestationNegative,
                score.lastComputedAt);
    }
}
