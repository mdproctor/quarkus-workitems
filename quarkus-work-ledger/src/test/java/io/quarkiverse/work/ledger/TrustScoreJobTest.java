package io.quarkiverse.work.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.ActorTrustScore;
import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.AttestationVerdict;
import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.repository.ActorTrustScoreRepository;
import io.quarkiverse.ledger.runtime.service.TrustScoreJob;
import io.quarkiverse.work.ledger.model.WorkItemLedgerEntry;
import io.quarkiverse.work.ledger.repository.WorkItemLedgerEntryRepository;
import io.quarkiverse.work.runtime.model.WorkItem;
import io.quarkiverse.work.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.work.runtime.model.WorkItemPriority;
import io.quarkiverse.work.runtime.service.WorkItemService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for {@link TrustScoreJob}.
 *
 * <p>
 * RED-phase: these tests will not compile until {@link WorkItemLedgerEntry} and
 * {@link WorkItemLedgerEntryRepository} are created.
 */
@QuarkusTest
@TestTransaction
class TrustScoreJobTest {

    @Inject
    WorkItemService workItemService;

    @Inject
    TrustScoreJob trustScoreJob;

    @Inject
    ActorTrustScoreRepository trustScoreRepo;

    @Inject
    WorkItemLedgerEntryRepository ledgerRepo;

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private UUID createAndCompleteWorkItem(final String actor) {
        final WorkItemCreateRequest req = new WorkItemCreateRequest(
                "Trust test", null, null, null,
                WorkItemPriority.NORMAL, null, null, null, null,
                "system", null, null, null, null, null, null, null, null, null);
        final WorkItem wi = workItemService.create(req);
        workItemService.claim(wi.id, actor);
        workItemService.start(wi.id, actor);
        workItemService.complete(wi.id, actor, "{\"approved\":true}");
        return wi.id;
    }

    private void flagAllEntriesFor(final UUID workItemId, final String actor) {
        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(workItemId);
        for (final WorkItemLedgerEntry entry : entries) {
            if (actor.equals(entry.actorId)) {
                final LedgerAttestation attestation = new LedgerAttestation();
                attestation.ledgerEntryId = entry.id;
                attestation.subjectId = workItemId;
                attestation.attestorId = "audit-agent";
                attestation.attestorType = ActorType.AGENT;
                attestation.verdict = AttestationVerdict.FLAGGED;
                attestation.confidence = 0.9;
                ledgerRepo.saveAttestation(attestation);
            }
        }
    }

    private void soundMostRecentEntryFor(final UUID workItemId, final String actor) {
        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(workItemId);
        entries.stream()
                .filter(e -> actor.equals(e.actorId))
                .reduce((first, second) -> second)
                .ifPresent(entry -> {
                    final LedgerAttestation attestation = new LedgerAttestation();
                    attestation.ledgerEntryId = entry.id;
                    attestation.subjectId = workItemId;
                    attestation.attestorId = "audit-agent";
                    attestation.attestorType = ActorType.AGENT;
                    attestation.verdict = AttestationVerdict.SOUND;
                    attestation.confidence = 0.95;
                    ledgerRepo.saveAttestation(attestation);
                });
    }

    // -------------------------------------------------------------------------
    // No ledger data
    // -------------------------------------------------------------------------

    @Test
    void runComputation_noLedgerData_noScoresComputed() {
        trustScoreJob.runComputation();

        final List<ActorTrustScore> all = trustScoreRepo.findAll();
        assertThat(all).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Single actor
    // -------------------------------------------------------------------------

    @Test
    void runComputation_afterOneDecision_computesScore() {
        createAndCompleteWorkItem("alice");

        trustScoreJob.runComputation();

        final Optional<ActorTrustScore> aliceScore = trustScoreRepo.findByActorId("alice");
        assertThat(aliceScore).isPresent();
        assertThat(aliceScore.get().trustScore).isGreaterThanOrEqualTo(0.0);
        assertThat(aliceScore.get().trustScore).isLessThanOrEqualTo(1.0);
        assertThat(aliceScore.get().decisionCount).isGreaterThan(0);
    }

    @Test
    void runComputation_cleanDecisions_highScore() {
        createAndCompleteWorkItem("alice");
        createAndCompleteWorkItem("alice");
        createAndCompleteWorkItem("alice");

        trustScoreJob.runComputation();

        final Optional<ActorTrustScore> aliceScore = trustScoreRepo.findByActorId("alice");
        assertThat(aliceScore).isPresent();
        assertThat(aliceScore.get().trustScore).isGreaterThan(0.7);
    }

    // -------------------------------------------------------------------------
    // Challenged decisions
    // -------------------------------------------------------------------------

    @Test
    void runComputation_challengedDecisions_lowerScore() {
        final UUID wi1 = createAndCompleteWorkItem("alice");
        final UUID wi2 = createAndCompleteWorkItem("alice");
        flagAllEntriesFor(wi1, "alice");
        flagAllEntriesFor(wi2, "alice");

        trustScoreJob.runComputation();

        final Optional<ActorTrustScore> aliceScore = trustScoreRepo.findByActorId("alice");
        assertThat(aliceScore).isPresent();
        assertThat(aliceScore.get().trustScore).isLessThan(0.7);
    }

    // -------------------------------------------------------------------------
    // Two actors — scored independently
    // -------------------------------------------------------------------------

    @Test
    void runComputation_twoActors_scoredIndependently() {
        createAndCompleteWorkItem("alice");
        createAndCompleteWorkItem("alice");
        createAndCompleteWorkItem("alice");

        final UUID bobWi1 = createAndCompleteWorkItem("bob");
        final UUID bobWi2 = createAndCompleteWorkItem("bob");
        final UUID bobWi3 = createAndCompleteWorkItem("bob");
        flagAllEntriesFor(bobWi1, "bob");
        flagAllEntriesFor(bobWi2, "bob");
        flagAllEntriesFor(bobWi3, "bob");

        trustScoreJob.runComputation();

        final Optional<ActorTrustScore> aliceScore = trustScoreRepo.findByActorId("alice");
        final Optional<ActorTrustScore> bobScore = trustScoreRepo.findByActorId("bob");

        assertThat(aliceScore).isPresent();
        assertThat(bobScore).isPresent();
        assertThat(aliceScore.get().trustScore).isGreaterThan(bobScore.get().trustScore);
    }

    // -------------------------------------------------------------------------
    // Score update on second run
    // -------------------------------------------------------------------------

    @Test
    void runComputation_twice_updatesScore() {
        createAndCompleteWorkItem("alice");

        trustScoreJob.runComputation();

        final Optional<ActorTrustScore> firstRun = trustScoreRepo.findByActorId("alice");
        assertThat(firstRun).isPresent();
        final int firstDecisionCount = firstRun.get().decisionCount;
        final Instant firstComputedAt = firstRun.get().lastComputedAt;

        createAndCompleteWorkItem("alice");
        createAndCompleteWorkItem("alice");

        trustScoreJob.runComputation();

        final Optional<ActorTrustScore> secondRun = trustScoreRepo.findByActorId("alice");
        assertThat(secondRun).isPresent();
        assertThat(secondRun.get().decisionCount).isGreaterThan(firstDecisionCount);
        assertThat(secondRun.get().lastComputedAt).isAfterOrEqualTo(firstComputedAt);
    }

    // -------------------------------------------------------------------------
    // SOUND attestation improves positive counting
    // -------------------------------------------------------------------------

    @Test
    void runComputation_soundAttestation_improvesCounting() {
        final UUID workItemId = createAndCompleteWorkItem("alice");
        soundMostRecentEntryFor(workItemId, "alice");

        trustScoreJob.runComputation();

        final Optional<ActorTrustScore> aliceScore = trustScoreRepo.findByActorId("alice");
        assertThat(aliceScore).isPresent();
        assertThat(aliceScore.get().attestationPositive).isGreaterThan(0);
    }
}
