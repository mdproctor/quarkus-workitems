package io.quarkiverse.work.examples.moderation;

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
import io.quarkiverse.ledger.runtime.model.supplement.ComplianceSupplement;
import io.quarkiverse.ledger.runtime.model.supplement.ProvenanceSupplement;
import io.quarkiverse.work.examples.ScenarioResponse;
import io.quarkiverse.work.examples.StepLog;
import io.quarkiverse.work.examples.moderation.MockAIClassifier.ContentFlag;
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
 * Scenario 3 — AI Content Moderation.
 *
 * <p>
 * Demonstrates the agent/human hybrid lifecycle: an AI content classifier flags a post,
 * a human moderator overrides the AI decision with a formal rationale, and a compliance
 * bot attests the override. Illustrates actorType derivation (AGENT vs HUMAN via the
 * {@code agent:} prefix), AI evidence capture on the creation entry, provenance linkage
 * to the source content-ai system, and AGENT attestation on a human decision.
 *
 * <p>
 * Actors:
 * <ul>
 * <li>{@code agent:content-ai} — creates the moderation WorkItem (AGENT, evidence from classifier)</li>
 * <li>{@code moderator-dana} — claims, starts, and rejects with formal rationale (HUMAN)</li>
 * <li>{@code agent:compliance-bot} — attests the rejection as ENDORSED (AGENT)</li>
 * </ul>
 *
 * <p>
 * Endpoint: {@code POST /examples/moderation/run}
 */
@Path("/examples/moderation")
@Produces(MediaType.APPLICATION_JSON)
public class ContentModerationScenario {

    private static final Logger LOG = Logger.getLogger(ContentModerationScenario.class);

    private static final String SCENARIO_ID = "content-moderation";
    private static final String ACTOR_CREATOR = "agent:content-ai";
    private static final String ACTOR_MODERATOR = "moderator-dana";
    private static final String ACTOR_COMPLIANCE_BOT = "agent:compliance-bot";

    @Inject
    WorkItemService workItemService;

    @Inject
    WorkItemLedgerEntryRepository ledgerRepo;

    @Inject
    AuditEntryStore auditStore;

    @Inject
    MockAIClassifier classifier;

    /**
     * Run the content moderation scenario from end to end and return the full ledger and audit trail.
     *
     * @return scenario response containing steps, ledger entries, and audit trail
     */
    @POST
    @Path("/run")
    @Transactional
    public ScenarioResponse run() {
        final List<StepLog> steps = new ArrayList<>();
        final int total = 4;

        // AI classifier analyses the post
        final ContentFlag flag = classifier.analyse("sample post content");

        // Step 1: AI agent creates the moderation WorkItem — agent: prefix → actorType=AGENT in ledger
        final String description1 = "agent:content-ai flags post POST-9912 for hate-speech (confidence=0.73)";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 1, total, description1);

        final WorkItemCreateRequest request = new WorkItemCreateRequest(
                "Content moderation: POST-9912",
                "AI classifier flagged post POST-9912 for potential policy violation",
                "content-moderation",
                null,
                WorkItemPriority.HIGH,
                null,
                "content-moderators",
                null,
                null,
                ACTOR_CREATOR,
                "{\"postId\": \"POST-9912\", \"flagReason\": \"hate-speech\"}",
                null,
                null,
                null,
                null,
                null,
                null, null, null);

        final WorkItem wi = workItemService.create(request);
        steps.add(new StepLog(1, description1, wi.id));

        // Set evidence and provenance on the creation ledger entry
        final WorkItemLedgerEntry creationEntry = ledgerRepo.findByWorkItemId(wi.id).stream()
                .filter(e -> e.sequenceNumber == 1)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No creation ledger entry found"));
        final var creationCompliance = new ComplianceSupplement();
        creationCompliance.evidence = flag.toEvidenceJson();
        creationEntry.attach(creationCompliance);
        final var creationProvenance = new ProvenanceSupplement();
        creationProvenance.sourceEntityId = "POST-9912";
        creationProvenance.sourceEntityType = "ContentFlag";
        creationProvenance.sourceEntitySystem = "content-ai";
        creationEntry.attach(creationProvenance);

        // Step 2: moderator-dana claims the WorkItem
        final String description2 = "moderator-dana claims the moderation WorkItem";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 2, total, description2);
        workItemService.claim(wi.id, ACTOR_MODERATOR);
        steps.add(new StepLog(2, description2, wi.id));

        // Step 3: moderator-dana starts the WorkItem
        final String description3 = "moderator-dana starts reviewing the flagged post";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 3, total, description3);
        workItemService.start(wi.id, ACTOR_MODERATOR);
        steps.add(new StepLog(3, description3, wi.id));

        // Step 4: moderator-dana rejects with reason + rationale (4-arg overload)
        final String description4 = "moderator-dana rejects — content is satire, not hate speech";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 4, total, description4);
        workItemService.reject(
                wi.id,
                ACTOR_MODERATOR,
                "Content violates community guidelines",
                "Context review: satire, not hate speech");
        steps.add(new StepLog(4, description4, wi.id));

        // Add ENDORSED attestation from compliance bot (AGENT) on the rejection entry
        final WorkItemLedgerEntry rejectionEntry = ledgerRepo.findLatestByWorkItemId(wi.id)
                .orElseThrow(() -> new IllegalStateException("No rejection ledger entry found"));

        final LedgerAttestation attestation = new LedgerAttestation();
        attestation.ledgerEntryId = rejectionEntry.id;
        attestation.subjectId = wi.id;
        attestation.attestorId = ACTOR_COMPLIANCE_BOT;
        attestation.attestorType = ActorType.AGENT;
        attestation.verdict = AttestationVerdict.ENDORSED;
        attestation.confidence = 0.88;
        attestation.evidence = "{\"policyCheck\": \"passed\", \"ruleSet\": \"satire-exemption-v1\"}";
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
