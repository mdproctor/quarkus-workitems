package io.quarkiverse.work.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.AttestationVerdict;
import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.service.LedgerMerkleTree;
import io.quarkiverse.work.ledger.model.WorkItemLedgerEntry;
import io.quarkiverse.work.ledger.repository.WorkItemLedgerEntryRepository;
import io.quarkiverse.work.runtime.event.WorkItemLifecycleEvent;
import io.quarkiverse.work.runtime.model.WorkItem;
import io.quarkiverse.work.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.work.runtime.model.WorkItemPriority;
import io.quarkiverse.work.runtime.model.WorkItemRelation;
import io.quarkiverse.work.runtime.model.WorkItemRelationType;
import io.quarkiverse.work.runtime.service.WorkItemService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for the WorkItems Ledger module.
 *
 * <p>
 * Verifies that {@code WorkItemLifecycleEvent} CDI events are observed and converted
 * into {@link WorkItemLedgerEntry} records with correct field values, sequence numbering,
 * hash chain integrity, and attestation storage.
 *
 * <p>
 * RED-phase: these tests will not compile until {@link WorkItemLedgerEntry} and
 * {@link WorkItemLedgerEntryRepository} are created as part of the quarkus-ledger migration.
 */
@QuarkusTest
@TestTransaction
class LedgerIntegrationTest {

    @Inject
    WorkItemService workItemService;

    @Inject
    WorkItemLedgerEntryRepository ledgerRepo;

    @Inject
    Event<WorkItemLifecycleEvent> lifecycleEvent;

    // -------------------------------------------------------------------------
    // Fixture helper
    // -------------------------------------------------------------------------

    private WorkItemCreateRequest basicRequest(final String title) {
        return new WorkItemCreateRequest(title, null, null, null,
                WorkItemPriority.NORMAL, null, null, null, null, "system",
                null, null, null, null, null, null, null, null, null);
    }

    // -------------------------------------------------------------------------
    // WorkItemLedgerEntry written for every lifecycle transition
    // -------------------------------------------------------------------------

    @Test
    void create_writesOneLedgerEntry() {
        final var item = workItemService.create(basicRequest("Create test"));
        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);

        assertThat(entries).hasSize(1);
        final WorkItemLedgerEntry e = entries.get(0);
        assertThat(e.entryType).isEqualTo(LedgerEntryType.EVENT);
        assertThat(e.eventType).isEqualTo("WorkItemCreated");
        assertThat(e.commandType).isEqualTo("CreateWorkItem");
        assertThat(e.actorId).isEqualTo("system");
        assertThat(e.subjectId).isEqualTo(item.id);
    }

    @Test
    void claim_writesLedgerEntry() {
        final var item = workItemService.create(basicRequest("Claim test"));
        workItemService.claim(item.id, "alice");

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        assertThat(entries).hasSize(2);

        final WorkItemLedgerEntry claim = entries.get(1);
        assertThat(claim.eventType).isEqualTo("WorkItemAssigned");
        assertThat(claim.commandType).isEqualTo("ClaimWorkItem");
        assertThat(claim.actorId).isEqualTo("alice");
        assertThat(claim.actorRole).isEqualTo("Claimant");
    }

    @Test
    void start_writesLedgerEntry() {
        final var item = workItemService.create(basicRequest("Start test"));
        workItemService.claim(item.id, "alice");
        workItemService.start(item.id, "alice");

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        assertThat(entries).hasSize(3);

        final WorkItemLedgerEntry start = entries.get(2);
        assertThat(start.eventType).isEqualTo("WorkItemStarted");
        assertThat(start.commandType).isEqualTo("StartWorkItem");
    }

    @Test
    void complete_writesLedgerEntry() {
        final var item = workItemService.create(basicRequest("Complete test"));
        workItemService.claim(item.id, "alice");
        workItemService.start(item.id, "alice");
        workItemService.complete(item.id, "alice", "All done");

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        assertThat(entries).hasSize(4);

        final WorkItemLedgerEntry complete = entries.get(3);
        assertThat(complete.eventType).isEqualTo("WorkItemCompleted");
        assertThat(complete.commandType).isEqualTo("CompleteWorkItem");
    }

    @Test
    void reject_writesLedgerEntry() {
        final var item = workItemService.create(basicRequest("Reject test"));
        workItemService.claim(item.id, "alice");
        workItemService.reject(item.id, "alice", "Not my responsibility");

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        assertThat(entries).hasSize(3);

        final WorkItemLedgerEntry reject = entries.get(2);
        assertThat(reject.eventType).isEqualTo("WorkItemRejected");
        assertThat(reject.commandType).isEqualTo("RejectWorkItem");
    }

    @Test
    void delegate_writesLedgerEntry() {
        final var item = workItemService.create(basicRequest("Delegate test"));
        workItemService.claim(item.id, "alice");
        workItemService.delegate(item.id, "alice", "bob");

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        assertThat(entries).hasSize(3);

        final WorkItemLedgerEntry delegate = entries.get(2);
        assertThat(delegate.eventType).isEqualTo("WorkItemDelegated");
        assertThat(delegate.actorRole).isEqualTo("Delegator");
    }

    @Test
    void cancel_writesLedgerEntry() {
        final var item = workItemService.create(basicRequest("Cancel test"));
        workItemService.cancel(item.id, "admin", "No longer needed");

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        assertThat(entries).hasSize(2);

        final WorkItemLedgerEntry cancel = entries.get(1);
        assertThat(cancel.eventType).isEqualTo("WorkItemCancelled");
    }

    // -------------------------------------------------------------------------
    // subjectId == workItemId
    // -------------------------------------------------------------------------

    @Test
    void subjectId_equalsWorkItemId() {
        final var item = workItemService.create(basicRequest("SubjectId test"));

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).subjectId).isEqualTo(item.id);
    }

    // -------------------------------------------------------------------------
    // sequenceNumber
    // -------------------------------------------------------------------------

    @Test
    void sequenceNumber_incrementsPerWorkItem() {
        final var item = workItemService.create(basicRequest("Sequence test"));
        workItemService.claim(item.id, "alice");
        workItemService.start(item.id, "alice");
        workItemService.complete(item.id, "alice", "Done");

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        assertThat(entries).hasSize(4);
        assertThat(entries.get(0).sequenceNumber).isEqualTo(1);
        assertThat(entries.get(1).sequenceNumber).isEqualTo(2);
        assertThat(entries.get(2).sequenceNumber).isEqualTo(3);
        assertThat(entries.get(3).sequenceNumber).isEqualTo(4);
    }

    @Test
    void sequenceNumber_independentAcrossWorkItems() {
        final var item1 = workItemService.create(basicRequest("Item 1"));
        final var item2 = workItemService.create(basicRequest("Item 2"));

        final List<WorkItemLedgerEntry> entries1 = ledgerRepo.findByWorkItemId(item1.id);
        final List<WorkItemLedgerEntry> entries2 = ledgerRepo.findByWorkItemId(item2.id);

        assertThat(entries1).hasSize(1);
        assertThat(entries2).hasSize(1);
        assertThat(entries1.get(0).sequenceNumber).isEqualTo(1);
        assertThat(entries2.get(0).sequenceNumber).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Hash chain
    // -------------------------------------------------------------------------

    @Test
    void hashChain_firstEntryHasNonNullDigest() {
        final var item = workItemService.create(basicRequest("Hash test 1"));

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).digest).isNotNull();
    }

    @Test
    void hashChain_bothEntriesHaveDistinctDigests() {
        final var item = workItemService.create(basicRequest("Hash test 2"));
        workItemService.claim(item.id, "alice");

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).digest).isNotNull();
        assertThat(entries.get(1).digest).isNotNull();
        assertThat(entries.get(0).digest).isNotEqualTo(entries.get(1).digest);
    }

    @Test
    void hashChain_fullPathDigestsMatchLeafHashes() {
        final var item = workItemService.create(basicRequest("Hash verify test"));
        workItemService.claim(item.id, "alice");
        workItemService.start(item.id, "alice");
        workItemService.complete(item.id, "alice", "Verified");

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        assertThat(entries).hasSize(4);
        // Each entry's stored digest must equal LedgerMerkleTree.leafHash(entry)
        final boolean allValid = entries.stream()
                .allMatch(e -> e.digest != null && e.digest.equals(LedgerMerkleTree.leafHash(e)));
        assertThat(allValid).isTrue();
    }

    @Test
    void hashChainEnabled_digestIsNotNull() {
        final var item = workItemService.create(basicRequest("Digest non-null test"));

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).digest).isNotNull();
    }

    // -------------------------------------------------------------------------
    // decisionContext
    // -------------------------------------------------------------------------

    @Test
    void decisionContext_capturedOnCreate() {
        final var item = workItemService.create(basicRequest("DecisionContext create test"));

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).compliance().map(c -> c.decisionContext).orElse(null)).isNotNull();
        assertThat(entries.get(0).compliance().map(c -> c.decisionContext).orElse(null)).containsIgnoringCase("PENDING");
    }

    @Test
    void decisionContext_capturedOnComplete() {
        final var item = workItemService.create(basicRequest("DecisionContext complete test"));
        workItemService.claim(item.id, "alice");
        workItemService.start(item.id, "alice");
        workItemService.complete(item.id, "alice", "Finished");

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        assertThat(entries).hasSize(4);
        assertThat(entries.get(3).compliance().map(c -> c.decisionContext).orElse(null)).isNotNull();
        assertThat(entries.get(3).compliance().map(c -> c.decisionContext).orElse(null)).containsIgnoringCase("COMPLETED");
    }

    // -------------------------------------------------------------------------
    // commandType / eventType mapping
    // -------------------------------------------------------------------------

    @Test
    void commandAndEventType_matchExpectedMapping() {
        final var item = workItemService.create(basicRequest("Mapping test"));
        workItemService.claim(item.id, "bob");

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        assertThat(entries).hasSize(2);

        assertThat(entries.get(0).commandType).isEqualTo("CreateWorkItem");
        assertThat(entries.get(0).eventType).isEqualTo("WorkItemCreated");
        assertThat(entries.get(1).commandType).isEqualTo("ClaimWorkItem");
        assertThat(entries.get(1).eventType).isEqualTo("WorkItemAssigned");
    }

    // -------------------------------------------------------------------------
    // Attestation
    // -------------------------------------------------------------------------

    @Test
    void attestation_savedAndRetrievable() {
        final var item = workItemService.create(basicRequest("Attestation test"));
        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        assertThat(entries).hasSize(1);
        final UUID entryId = entries.get(0).id;

        final LedgerAttestation attestation = new LedgerAttestation();
        attestation.ledgerEntryId = entryId;
        attestation.subjectId = item.id;
        attestation.attestorId = "alice";
        attestation.attestorType = ActorType.HUMAN;
        attestation.verdict = AttestationVerdict.SOUND;
        attestation.evidence = "All checks passed";
        attestation.confidence = 0.9;
        ledgerRepo.saveAttestation(attestation);

        final List<LedgerAttestation> results = ledgerRepo.findAttestationsByEntryId(entryId);
        assertThat(results).hasSize(1);

        final LedgerAttestation saved = results.get(0);
        assertThat(saved.attestorId).isEqualTo("alice");
        assertThat(saved.attestorType).isEqualTo(ActorType.HUMAN);
        assertThat(saved.verdict).isEqualTo(AttestationVerdict.SOUND);
        assertThat(saved.confidence).isEqualTo(0.9);
        assertThat(saved.subjectId).isEqualTo(item.id);
    }

    @Test
    void attestation_multipleAttestationsOnSameEntry() {
        final var item = workItemService.create(basicRequest("Multi-attestation test"));
        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        final UUID entryId = entries.get(0).id;

        final LedgerAttestation a1 = new LedgerAttestation();
        a1.ledgerEntryId = entryId;
        a1.subjectId = item.id;
        a1.attestorId = "alice";
        a1.attestorType = ActorType.HUMAN;
        a1.verdict = AttestationVerdict.SOUND;
        a1.confidence = 0.9;
        ledgerRepo.saveAttestation(a1);

        final LedgerAttestation a2 = new LedgerAttestation();
        a2.ledgerEntryId = entryId;
        a2.subjectId = item.id;
        a2.attestorId = "audit-agent";
        a2.attestorType = ActorType.AGENT;
        a2.verdict = AttestationVerdict.ENDORSED;
        a2.confidence = 0.95;
        ledgerRepo.saveAttestation(a2);

        final List<LedgerAttestation> results = ledgerRepo.findAttestationsByEntryId(entryId);
        assertThat(results).hasSize(2);
        assertThat(results.stream().map(a -> a.attestorId).toList())
                .containsExactlyInAnyOrder("alice", "audit-agent");
    }

    // -------------------------------------------------------------------------
    // rationale and planRef captured via overloaded complete()
    // -------------------------------------------------------------------------

    @Test
    void complete_withRationaleAndPlanRef_capturedInLedger() {
        final var item = workItemService.create(new WorkItemCreateRequest(
                "Rationale complete test", null, null, null,
                WorkItemPriority.NORMAL, null, null, null, null,
                "system", null, null, null, null, null, null, null, null, null));
        workItemService.claim(item.id, "alice");
        workItemService.start(item.id, "alice");

        workItemService.complete(item.id, "alice",
                "{\"decision\":\"approved\"}",
                "Income verified against payslips",
                "credit-policy-v2.1");

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        final WorkItemLedgerEntry completionEntry = entries.stream()
                .filter(e -> "WorkItemCompleted".equals(e.eventType))
                .findFirst().orElseThrow();
        assertThat(completionEntry.compliance().map(c -> c.rationale).orElse(null))
                .isEqualTo("Income verified against payslips");
        assertThat(completionEntry.compliance().map(c -> c.planRef).orElse(null)).isEqualTo("credit-policy-v2.1");
    }

    // -------------------------------------------------------------------------
    // rationale captured via overloaded reject()
    // -------------------------------------------------------------------------

    @Test
    void reject_withRationale_capturedInLedger() {
        final var item = workItemService.create(new WorkItemCreateRequest(
                "Rationale reject test", null, null, null,
                WorkItemPriority.NORMAL, null, null, null, null,
                "system", null, null, null, null, null, null, null, null, null));
        workItemService.claim(item.id, "alice");

        workItemService.reject(item.id, "alice",
                "Content violates guidelines",
                "Context review: satire, not hate speech");

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        final WorkItemLedgerEntry rejectionEntry = entries.stream()
                .filter(e -> "WorkItemRejected".equals(e.eventType))
                .findFirst().orElseThrow();
        assertThat(rejectionEntry.compliance().map(c -> c.rationale).orElse(null))
                .isEqualTo("Context review: satire, not hate speech");
        assertThat(rejectionEntry.compliance().map(c -> c.detail).orElse(null)).isEqualTo("Content violates guidelines");
    }

    // -------------------------------------------------------------------------
    // actorType derivation from actorId prefix convention
    // -------------------------------------------------------------------------

    @Test
    void create_humanActor_actorTypeIsHuman() {
        final var item = workItemService.create(new WorkItemCreateRequest(
                "Human actor test", null, null, null,
                WorkItemPriority.NORMAL, null, null, null, null,
                "alice", null, null, null, null, null, null, null, null, null));

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).actorType).isEqualTo(ActorType.HUMAN);
    }

    @Test
    void create_agentPrefixActor_actorTypeIsAgent() {
        final var item = workItemService.create(new WorkItemCreateRequest(
                "Agent actor test", null, null, null,
                WorkItemPriority.NORMAL, null, null, null, null,
                "agent:content-ai", null, null, null, null, null, null, null, null, null));

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).actorType).isEqualTo(ActorType.AGENT);
    }

    @Test
    void create_systemPrefixActor_actorTypeIsSystem() {
        final var item = workItemService.create(new WorkItemCreateRequest(
                "System actor test", null, null, null,
                WorkItemPriority.NORMAL, null, null, null, null,
                "system:scheduler", null, null, null, null, null, null, null, null, null));

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(item.id);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).actorType).isEqualTo(ActorType.SYSTEM);
    }

    // -------------------------------------------------------------------------
    // Causal chain: spawn wires causedByEntryId on child CREATED entries
    // -------------------------------------------------------------------------

    @Test
    void spawn_createsCausalChain_inLedger() {
        // Create parent
        final WorkItem parent = workItemService.create(basicRequest("Spawn parent"));

        // Create child with callerRef, as WorkItemSpawnService would
        final WorkItem child = workItemService.create(new WorkItemCreateRequest(
                "Spawn child", null, null, null,
                WorkItemPriority.NORMAL, null, null, null, null,
                "system:spawn", null, null, null, null, null, null, "task-A", null, null));

        // Wire PART_OF relation: child → parent (mirrors WorkItemSpawnService.spawn)
        final WorkItemRelation rel = new WorkItemRelation();
        rel.sourceId = child.id;
        rel.targetId = parent.id;
        rel.relationType = WorkItemRelationType.PART_OF;
        rel.createdBy = "system:spawn";
        rel.persist();

        // Fire SPAWNED event on parent, as WorkItemSpawnService would
        lifecycleEvent.fire(WorkItemLifecycleEvent.of("SPAWNED", parent, "system:spawn",
                "children:1"));

        // Fetch ledger entries
        final List<WorkItemLedgerEntry> parentEntries = ledgerRepo.findByWorkItemId(parent.id);
        final List<WorkItemLedgerEntry> childEntries = ledgerRepo.findByWorkItemId(child.id);

        // Parent should have CREATED + SPAWNED entries
        assertThat(parentEntries).hasSize(2);
        final WorkItemLedgerEntry parentSpawned = parentEntries.stream()
                .filter(e -> "WorkItemsSpawned".equals(e.eventType))
                .findFirst().orElseThrow(() -> new AssertionError("No WorkItemsSpawned entry on parent"));
        assertThat(parentSpawned.commandType).isEqualTo("SpawnWorkItems");

        // Child should have exactly one CREATED entry
        assertThat(childEntries).hasSize(1);
        final WorkItemLedgerEntry childCreated = childEntries.get(0);
        assertThat(childCreated.eventType).isEqualTo("WorkItemCreated");

        // causedByEntryId on child CREATED must point to parent SPAWNED entry
        assertThat(childCreated.causedByEntryId)
                .as("child CREATED causedByEntryId should point to parent SPAWNED entry")
                .isEqualTo(parentSpawned.id);
    }
}
