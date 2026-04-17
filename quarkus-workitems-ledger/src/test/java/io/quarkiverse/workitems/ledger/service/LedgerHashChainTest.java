package io.quarkiverse.workitems.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.service.LedgerHashChain;
import io.quarkiverse.workitems.ledger.model.WorkItemLedgerEntry;

/**
 * Pure JUnit 5 unit tests for {@link LedgerHashChain} using WorkItems's
 * {@link WorkItemLedgerEntry} subclass — no Quarkus runtime, no CDI.
 *
 * <p>
 * The canonical form covers base-class fields only:
 * {@code subjectId|seqNum|entryType|actorId|actorRole|planRef|occurredAt}.
 * WorkItem-specific fields ({@code commandType}, {@code eventType}) are intentionally
 * excluded from the chain — subclass fields do not participate in tamper detection.
 * This is verified explicitly in this test suite.
 *
 * <p>
 * RED-phase: these tests will not compile until {@link WorkItemLedgerEntry} is created.
 */
class LedgerHashChainTest {

    // -------------------------------------------------------------------------
    // Fixture helper
    // -------------------------------------------------------------------------

    private WorkItemLedgerEntry entry(final UUID workItemId, final int seq) {
        final WorkItemLedgerEntry e = new WorkItemLedgerEntry();
        e.subjectId = workItemId;
        e.sequenceNumber = seq;
        e.entryType = LedgerEntryType.EVENT;
        e.commandType = "CreateWorkItem";
        e.eventType = "WorkItemCreated";
        e.actorId = "system";
        e.actorRole = "Initiator";
        // planRef now in ComplianceSupplement — not needed in hash chain test
        e.occurredAt = Instant.parse("2026-04-14T10:00:00Z");
        return e;
    }

    // -------------------------------------------------------------------------
    // compute — basic contract
    // -------------------------------------------------------------------------

    @Test
    void compute_returnsNonNullDigest() {
        final WorkItemLedgerEntry e = entry(UUID.randomUUID(), 1);

        assertThat(LedgerHashChain.compute(null, e)).isNotNull();
    }

    @Test
    void compute_withNullPreviousHash_isDeterministic() {
        final WorkItemLedgerEntry e = entry(UUID.randomUUID(), 1);

        final String first = LedgerHashChain.compute(null, e);
        final String second = LedgerHashChain.compute(null, e);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void compute_differentPreviousHash_producesDifferentDigest() {
        final WorkItemLedgerEntry e = entry(UUID.randomUUID(), 2);

        final String withNull = LedgerHashChain.compute(null, e);
        final String withPrev = LedgerHashChain.compute("abc123", e);

        assertThat(withNull).isNotEqualTo(withPrev);
    }

    @Test
    void compute_mutatingSubjectId_changesDifferentDigest() {
        final WorkItemLedgerEntry e = entry(UUID.randomUUID(), 1);
        final String before = LedgerHashChain.compute(null, e);

        e.subjectId = UUID.randomUUID();
        final String after = LedgerHashChain.compute(null, e);

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    void compute_mutatingActorId_changesDifferentDigest() {
        final WorkItemLedgerEntry e = entry(UUID.randomUUID(), 1);
        final String before = LedgerHashChain.compute(null, e);

        e.actorId = "mutated-actor";
        final String after = LedgerHashChain.compute(null, e);

        assertThat(before).isNotEqualTo(after);
    }

    // -------------------------------------------------------------------------
    // Subclass fields are excluded from the canonical form (by design)
    // -------------------------------------------------------------------------

    @Test
    void compute_mutatingCommandType_doesNotChangeDigest() {
        // commandType is WorkItemLedgerEntry-specific — not in canonical form
        final WorkItemLedgerEntry e = entry(UUID.randomUUID(), 1);
        final String before = LedgerHashChain.compute(null, e);

        e.commandType = "mutated-command";
        final String after = LedgerHashChain.compute(null, e);

        assertThat(before).isEqualTo(after);
    }

    @Test
    void compute_mutatingEventType_doesNotChangeDigest() {
        // eventType is WorkItemLedgerEntry-specific — not in canonical form
        final WorkItemLedgerEntry e = entry(UUID.randomUUID(), 1);
        final String before = LedgerHashChain.compute(null, e);

        e.eventType = "mutated-event";
        final String after = LedgerHashChain.compute(null, e);

        assertThat(before).isEqualTo(after);
    }

    // -------------------------------------------------------------------------
    // verify — empty and single-entry chains
    // -------------------------------------------------------------------------

    @Test
    void verify_emptyList_returnsTrue() {
        assertThat(LedgerHashChain.verify(Collections.emptyList())).isTrue();
    }

    @Test
    void verify_singleEntry_withNullPrevious_returnsTrue() {
        final WorkItemLedgerEntry e = entry(UUID.randomUUID(), 1);
        e.previousHash = null;
        e.digest = LedgerHashChain.compute(null, e);

        assertThat(LedgerHashChain.verify(List.of(e))).isTrue();
    }

    // -------------------------------------------------------------------------
    // verify — multi-entry chains
    // -------------------------------------------------------------------------

    @Test
    void verify_twoEntries_validChain_returnsTrue() {
        final UUID id = UUID.randomUUID();

        final WorkItemLedgerEntry e1 = entry(id, 1);
        e1.previousHash = null;
        e1.digest = LedgerHashChain.compute(null, e1);

        final WorkItemLedgerEntry e2 = entry(id, 2);
        e2.commandType = "ClaimWorkItem";
        e2.eventType = "WorkItemAssigned";
        e2.actorId = "alice";
        e2.previousHash = e1.digest;
        e2.digest = LedgerHashChain.compute(e1.digest, e2);

        assertThat(LedgerHashChain.verify(List.of(e1, e2))).isTrue();
    }

    @Test
    void verify_twoEntries_tamperedSecondEntry_returnsFalse() {
        final UUID id = UUID.randomUUID();

        final WorkItemLedgerEntry e1 = entry(id, 1);
        e1.previousHash = null;
        e1.digest = LedgerHashChain.compute(null, e1);

        final WorkItemLedgerEntry e2 = entry(id, 2);
        e2.actorId = "alice";
        e2.previousHash = e1.digest;
        e2.digest = LedgerHashChain.compute(e1.digest, e2);

        // Tamper: mutate actorId without recomputing digest
        e2.actorId = "mallory";

        assertThat(LedgerHashChain.verify(List.of(e1, e2))).isFalse();
    }

    @Test
    void verify_fourEntries_fullLifecyclePath_returnsTrue() {
        final UUID id = UUID.randomUUID();

        final WorkItemLedgerEntry create = entry(id, 1);
        create.previousHash = null;
        create.digest = LedgerHashChain.compute(null, create);

        final WorkItemLedgerEntry claim = entry(id, 2);
        claim.commandType = "ClaimWorkItem";
        claim.eventType = "WorkItemAssigned";
        claim.actorId = "alice";
        claim.actorRole = "Claimant";
        claim.previousHash = create.digest;
        claim.digest = LedgerHashChain.compute(create.digest, claim);

        final WorkItemLedgerEntry start = entry(id, 3);
        start.commandType = "StartWorkItem";
        start.eventType = "WorkItemStarted";
        start.actorId = "alice";
        start.actorRole = "Assignee";
        start.previousHash = claim.digest;
        start.digest = LedgerHashChain.compute(claim.digest, start);

        final WorkItemLedgerEntry complete = entry(id, 4);
        complete.commandType = "CompleteWorkItem";
        complete.eventType = "WorkItemCompleted";
        complete.actorId = "alice";
        complete.actorRole = "Resolver";
        complete.previousHash = start.digest;
        complete.digest = LedgerHashChain.compute(start.digest, complete);

        final List<LedgerEntry> entries = List.of(create, claim, start, complete);
        assertThat(LedgerHashChain.verify(entries)).isTrue();
    }

    // -------------------------------------------------------------------------
    // genesisHash sentinel
    // -------------------------------------------------------------------------

    @Test
    void genesisHash_returnsGENESIS() {
        assertThat(LedgerHashChain.genesisHash()).isEqualTo("GENESIS");
    }
}
