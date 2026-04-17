package io.quarkiverse.workitems.ledger.api;

import java.util.List;

import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.supplement.ComplianceSupplement;
import io.quarkiverse.ledger.runtime.model.supplement.ProvenanceSupplement;
import io.quarkiverse.workitems.ledger.api.dto.LedgerAttestationResponse;
import io.quarkiverse.workitems.ledger.api.dto.LedgerEntryResponse;
import io.quarkiverse.workitems.ledger.model.WorkItemLedgerEntry;

/**
 * Static mapper between ledger JPA entities and REST response records.
 */
public final class LedgerMapper {

    private LedgerMapper() {
    }

    /**
     * Map a {@link LedgerAttestation} entity to its REST response representation.
     *
     * @param a the attestation entity; must not be {@code null}
     * @return the corresponding response record
     */
    public static LedgerAttestationResponse toResponse(final LedgerAttestation a) {
        return new LedgerAttestationResponse(
                a.id,
                a.ledgerEntryId,
                a.subjectId,
                a.attestorId,
                a.attestorType,
                a.attestorRole,
                a.verdict,
                a.evidence,
                a.confidence,
                a.occurredAt);
    }

    /**
     * Map a {@link WorkItemLedgerEntry} entity and its attestations to the REST response
     * representation.
     *
     * @param e the ledger entry entity; must not be {@code null}
     * @param attestations the list of attestations for this entry; may be empty but not {@code null}
     * @return the corresponding response record including mapped attestations
     */
    public static LedgerEntryResponse toResponse(final WorkItemLedgerEntry e,
            final List<LedgerAttestation> attestations) {
        // Access supplements directly via the list — avoids JVM method resolution
        // issues with inherited default methods on Hibernate-proxied subclasses
        final ComplianceSupplement comp = e.supplements == null ? null
                : e.supplements.stream()
                        .filter(s -> s instanceof ComplianceSupplement).map(s -> (ComplianceSupplement) s)
                        .findFirst().orElse(null);
        // ObservabilitySupplement not yet in this quarkus-ledger version — causedByEntryId/correlationId return null
        final ProvenanceSupplement prov = e.supplements == null ? null
                : e.supplements.stream()
                        .filter(s -> s instanceof ProvenanceSupplement).map(s -> (ProvenanceSupplement) s)
                        .findFirst().orElse(null);
        return new LedgerEntryResponse(
                e.id,
                e.subjectId,
                e.sequenceNumber,
                e.entryType,
                e.commandType,
                e.eventType,
                e.actorId,
                e.actorType,
                e.actorRole,
                comp != null ? comp.planRef : null,
                comp != null ? comp.rationale : null,
                comp != null ? comp.decisionContext : null,
                comp != null ? comp.evidence : null,
                comp != null ? comp.detail : null,
                null, // causedByEntryId — ObservabilitySupplement not in this quarkus-ledger version
                null, // correlationId — ObservabilitySupplement not in this quarkus-ledger version
                prov != null ? prov.sourceEntityId : null,
                prov != null ? prov.sourceEntityType : null,
                prov != null ? prov.sourceEntitySystem : null,
                e.previousHash,
                e.digest,
                e.occurredAt,
                attestations.stream().map(LedgerMapper::toResponse).toList());
    }
}
