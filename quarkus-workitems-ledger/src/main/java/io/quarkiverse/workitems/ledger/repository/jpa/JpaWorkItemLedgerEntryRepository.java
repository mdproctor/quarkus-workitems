package io.quarkiverse.workitems.ledger.repository.jpa;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.workitems.ledger.model.WorkItemLedgerEntry;
import io.quarkiverse.workitems.ledger.repository.WorkItemLedgerEntryRepository;

/**
 * Hibernate ORM / Panache implementation of {@link WorkItemLedgerEntryRepository}.
 *
 * <p>
 * This bean does NOT extend {@code JpaLedgerEntryRepository} from quarkus-ledger in order
 * to avoid CDI ambiguity — both would implement {@code LedgerEntryRepository} and the
 * container would see two eligible beans. Instead, this class directly implements
 * {@link WorkItemLedgerEntryRepository} in full, with typed Panache queries for
 * {@link WorkItemLedgerEntry} where specificity is needed.
 */
@ApplicationScoped
public class JpaWorkItemLedgerEntryRepository implements WorkItemLedgerEntryRepository {

    /** {@inheritDoc} */
    @Override
    public LedgerEntry save(final LedgerEntry entry) {
        entry.persist();
        return entry;
    }

    /** {@inheritDoc} */
    @Override
    public List<WorkItemLedgerEntry> findByWorkItemId(final UUID workItemId) {
        return WorkItemLedgerEntry.list("subjectId = ?1 ORDER BY sequenceNumber ASC", workItemId);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<WorkItemLedgerEntry> findLatestByWorkItemId(final UUID workItemId) {
        return WorkItemLedgerEntry.find("subjectId = ?1 ORDER BY sequenceNumber DESC", workItemId)
                .firstResultOptional();
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findBySubjectId(final UUID subjectId) {
        return LedgerEntry.list("subjectId = ?1 ORDER BY sequenceNumber ASC", subjectId);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<LedgerEntry> findLatestBySubjectId(final UUID subjectId) {
        return LedgerEntry.find("subjectId = ?1 ORDER BY sequenceNumber DESC", subjectId)
                .firstResultOptional();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<LedgerEntry> findById(final UUID id) {
        return Optional.ofNullable(LedgerEntry.findById(id));
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerAttestation> findAttestationsByEntryId(final UUID ledgerEntryId) {
        return LedgerAttestation.list("ledgerEntryId = ?1 ORDER BY occurredAt ASC", ledgerEntryId);
    }

    /** {@inheritDoc} */
    @Override
    public LedgerAttestation saveAttestation(final LedgerAttestation attestation) {
        attestation.persist();
        return attestation;
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findAllEvents() {
        return LedgerEntry.find("entryType = ?1", LedgerEntryType.EVENT).list();
    }

    /** {@inheritDoc} */
    @Override
    public Map<UUID, List<LedgerAttestation>> findAttestationsForEntries(final Set<UUID> entryIds) {
        if (entryIds.isEmpty()) {
            return Collections.emptyMap();
        }
        final List<LedgerAttestation> all = LedgerAttestation.list("ledgerEntryId IN ?1", entryIds);
        return all.stream().collect(Collectors.groupingBy(a -> a.ledgerEntryId));
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findByTimeRange(final Instant from, final Instant to) {
        return LedgerEntry.list("occurredAt >= ?1 AND occurredAt <= ?2 ORDER BY occurredAt ASC", from, to);
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findCausedBy(final UUID causedByEntryId) {
        // ObservabilitySupplement.causedByEntryId stored in supplement JSON — not yet queryable via JPQL
        return List.of();
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findByActorId(final String actorId, final Instant from, final Instant to) {
        return LedgerEntry.list(
                "actorId = ?1 AND occurredAt >= ?2 AND occurredAt <= ?3 ORDER BY occurredAt ASC",
                actorId, from, to);
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findByActorRole(final String actorRole, final Instant from, final Instant to) {
        return LedgerEntry.list(
                "actorRole = ?1 AND occurredAt >= ?2 AND occurredAt <= ?3 ORDER BY occurredAt ASC",
                actorRole, from, to);
    }
}
