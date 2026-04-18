package io.quarkiverse.workitems.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.model.LedgerMerkleFrontier;
import io.quarkiverse.ledger.runtime.service.LedgerMerkleTree;
import io.quarkiverse.workitems.ledger.model.WorkItemLedgerEntry;

/**
 * Pure JUnit 5 unit tests for {@link LedgerMerkleTree} using WorkItems's
 * {@link WorkItemLedgerEntry} subclass — no Quarkus runtime, no CDI.
 *
 * <p>
 * The canonical form covers base-class fields only:
 * {@code subjectId|seqNum|entryType|actorId|actorRole|occurredAt}.
 * WorkItem-specific fields ({@code commandType}, {@code eventType}) are
 * intentionally excluded — verified explicitly below.
 */
class LedgerMerkleHashTest {

    private WorkItemLedgerEntry entry(final UUID workItemId, final int seq) {
        final WorkItemLedgerEntry e = new WorkItemLedgerEntry();
        e.subjectId = workItemId;
        e.sequenceNumber = seq;
        e.entryType = LedgerEntryType.EVENT;
        e.commandType = "CreateWorkItem";
        e.eventType = "WorkItemCreated";
        e.actorId = "system";
        e.actorRole = "Initiator";
        e.occurredAt = Instant.parse("2026-04-14T10:00:00Z");
        return e;
    }

    // ── leafHash — basic contract ─────────────────────────────────────────────

    @Test
    void leafHash_returnsNonNullDigest() {
        assertThat(LedgerMerkleTree.leafHash(entry(UUID.randomUUID(), 1))).isNotNull();
    }

    @Test
    void leafHash_isDeterministic() {
        final WorkItemLedgerEntry e = entry(UUID.randomUUID(), 1);
        assertThat(LedgerMerkleTree.leafHash(e)).isEqualTo(LedgerMerkleTree.leafHash(e));
    }

    @Test
    void leafHash_mutatingSubjectId_changesDigest() {
        final WorkItemLedgerEntry e = entry(UUID.randomUUID(), 1);
        final String before = LedgerMerkleTree.leafHash(e);

        e.subjectId = UUID.randomUUID();
        assertThat(LedgerMerkleTree.leafHash(e)).isNotEqualTo(before);
    }

    @Test
    void leafHash_mutatingActorId_changesDigest() {
        final WorkItemLedgerEntry e = entry(UUID.randomUUID(), 1);
        final String before = LedgerMerkleTree.leafHash(e);

        e.actorId = "mutated-actor";
        assertThat(LedgerMerkleTree.leafHash(e)).isNotEqualTo(before);
    }

    @Test
    void leafHash_mutatingSequenceNumber_changesDigest() {
        final WorkItemLedgerEntry e = entry(UUID.randomUUID(), 1);
        final String before = LedgerMerkleTree.leafHash(e);

        e.sequenceNumber = 2;
        assertThat(LedgerMerkleTree.leafHash(e)).isNotEqualTo(before);
    }

    // ── Subclass fields excluded from canonical form (by design) ──────────────

    @Test
    void leafHash_mutatingCommandType_doesNotChangeDigest() {
        final WorkItemLedgerEntry e = entry(UUID.randomUUID(), 1);
        final String before = LedgerMerkleTree.leafHash(e);

        e.commandType = "mutated-command";
        assertThat(LedgerMerkleTree.leafHash(e)).isEqualTo(before);
    }

    @Test
    void leafHash_mutatingEventType_doesNotChangeDigest() {
        final WorkItemLedgerEntry e = entry(UUID.randomUUID(), 1);
        final String before = LedgerMerkleTree.leafHash(e);

        e.eventType = "mutated-event";
        assertThat(LedgerMerkleTree.leafHash(e)).isEqualTo(before);
    }

    // ── Integrity verification (replaces LedgerHashChain.verify) ─────────────

    @Test
    void integrity_singleEntry_digestMatchesLeafHash() {
        final WorkItemLedgerEntry e = entry(UUID.randomUUID(), 1);
        e.digest = LedgerMerkleTree.leafHash(e);

        assertThat(e.digest).isEqualTo(LedgerMerkleTree.leafHash(e));
    }

    @Test
    void integrity_tamperedEntry_digestMismatch() {
        final WorkItemLedgerEntry e = entry(UUID.randomUUID(), 1);
        e.digest = LedgerMerkleTree.leafHash(e);

        e.actorId = "mallory";
        assertThat(e.digest).isNotEqualTo(LedgerMerkleTree.leafHash(e));
    }

    @Test
    void integrity_fourEntries_allDigestsValid() {
        final UUID id = UUID.randomUUID();
        final List<WorkItemLedgerEntry> entries = List.of(
                withDigest(entry(id, 1)),
                withDigest(entry(id, 2)),
                withDigest(entry(id, 3)),
                withDigest(entry(id, 4)));

        final boolean allValid = entries.stream()
                .allMatch(e -> e.digest.equals(LedgerMerkleTree.leafHash(e)));
        assertThat(allValid).isTrue();
    }

    // ── Merkle Mountain Range frontier ────────────────────────────────────────

    @Test
    void mmr_singleLeaf_frontierHasOneNodeAtLevel0() {
        final WorkItemLedgerEntry e = withDigest(entry(UUID.randomUUID(), 1));
        final List<LedgerMerkleFrontier> frontier = LedgerMerkleTree.append(e.digest, List.of(), e.subjectId);

        assertThat(frontier).hasSize(1);
        assertThat(frontier.get(0).level).isEqualTo(0);
    }

    @Test
    void mmr_twoLeaves_mergeIntoLevel1() {
        final UUID id = UUID.randomUUID();
        List<LedgerMerkleFrontier> frontier = LedgerMerkleTree.append(
                withDigest(entry(id, 1)).digest, List.of(), id);
        frontier = LedgerMerkleTree.append(
                withDigest(entry(id, 2)).digest, frontier, id);

        // Two leaves merge into one level-1 node (perfect binary subtree)
        assertThat(frontier).hasSize(1);
        assertThat(frontier.get(0).level).isEqualTo(1);
    }

    @Test
    void mmr_threeLeaves_frontierHasTwoNodes() {
        final UUID id = UUID.randomUUID();
        List<LedgerMerkleFrontier> frontier = List.of();
        for (int i = 1; i <= 3; i++) {
            frontier = LedgerMerkleTree.append(
                    withDigest(entry(id, i)).digest, frontier, id);
        }
        // bitCount(3) = 2 → level-1 node + level-0 node
        assertThat(frontier).hasSize(2);
    }

    @Test
    void mmr_treeRoot_isReproducible() {
        final UUID id = UUID.randomUUID();
        List<LedgerMerkleFrontier> frontier = LedgerMerkleTree.append(
                withDigest(entry(id, 1)).digest, List.of(), id);
        frontier = LedgerMerkleTree.append(
                withDigest(entry(id, 2)).digest, frontier, id);

        assertThat(LedgerMerkleTree.treeRoot(frontier))
                .isEqualTo(LedgerMerkleTree.treeRoot(frontier));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private WorkItemLedgerEntry withDigest(final WorkItemLedgerEntry e) {
        e.digest = LedgerMerkleTree.leafHash(e);
        return e;
    }
}
