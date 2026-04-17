package io.quarkiverse.workitems.ledger.api;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import io.quarkiverse.ledger.runtime.config.LedgerConfig;
import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.supplement.ProvenanceSupplement;
import io.quarkiverse.workitems.ledger.api.dto.LedgerAttestationRequest;
import io.quarkiverse.workitems.ledger.api.dto.LedgerEntryResponse;
import io.quarkiverse.workitems.ledger.api.dto.ProvenanceRequest;
import io.quarkiverse.workitems.ledger.model.WorkItemLedgerEntry;
import io.quarkiverse.workitems.ledger.repository.WorkItemLedgerEntryRepository;
import io.quarkiverse.workitems.runtime.repository.WorkItemStore;
import io.quarkiverse.workitems.runtime.service.WorkItemNotFoundException;

/**
 * REST endpoints for the WorkItem ledger.
 *
 * <p>
 * All endpoints are under {@code /workitems/{id}} and are only present when the
 * {@code quarkus-workitems-ledger} module is on the classpath. The core extension provides none
 * of these — adding the module activates them automatically via CDI.
 */
@Path("/workitems/{id}")
@Produces(APPLICATION_JSON)
@ApplicationScoped
public class LedgerResource {

    @Inject
    WorkItemLedgerEntryRepository ledgerRepo;

    @Inject
    WorkItemStore workItemStore;

    @Inject
    LedgerConfig config;

    /**
     * Retrieve all ledger entries for a WorkItem, each with its attestations.
     *
     * @param workItemId the WorkItem UUID
     * @return ordered list of ledger entries with embedded attestations
     * @throws WorkItemNotFoundException if the WorkItem does not exist
     */
    @GET
    @Path("/ledger")
    public List<LedgerEntryResponse> getLedger(@PathParam("id") final UUID workItemId) {
        workItemStore.get(workItemId)
                .orElseThrow(() -> new WorkItemNotFoundException(workItemId));

        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(workItemId);
        return entries.stream()
                .map(e -> LedgerMapper.toResponse(e, ledgerRepo.findAttestationsByEntryId(e.id)))
                .toList();
    }

    /**
     * Set the source entity provenance on the creation ledger entry (sequence number 1).
     *
     * <p>
     * Called by integrating systems (Quarkus-Flow, CaseHub, Qhorus) immediately after WorkItem
     * creation to record which external entity originated the WorkItem.
     * Returns 409 Conflict if provenance has already been set.
     *
     * @param workItemId the WorkItem UUID
     * @param request the provenance data to set
     * @return 200 OK on success, 404 if WorkItem not found, 409 if provenance already set
     */
    @PUT
    @Path("/ledger/provenance")
    @Consumes(APPLICATION_JSON)
    @Transactional
    public Response setProvenance(@PathParam("id") final UUID workItemId,
            final ProvenanceRequest request) {
        workItemStore.get(workItemId)
                .orElseThrow(() -> new WorkItemNotFoundException(workItemId));

        // Find the creation entry (sequence number 1)
        final List<WorkItemLedgerEntry> entries = ledgerRepo.findByWorkItemId(workItemId);
        final WorkItemLedgerEntry creationEntry = entries.stream()
                .filter(e -> e.sequenceNumber == 1)
                .findFirst()
                .orElse(null);

        if (creationEntry == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"No ledger entry found for this WorkItem\"}")
                    .build();
        }

        if (creationEntry.provenance().isPresent()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("{\"error\":\"Provenance already set for this WorkItem\"}")
                    .build();
        }

        final var provenance = new ProvenanceSupplement();
        provenance.sourceEntityId = request.sourceEntityId();
        provenance.sourceEntityType = request.sourceEntityType();
        provenance.sourceEntitySystem = request.sourceEntitySystem();
        creationEntry.attach(provenance);
        ledgerRepo.save(creationEntry);

        return Response.ok().build();
    }

    /**
     * Post a peer attestation on a specific ledger entry.
     *
     * <p>
     * Returns 409 Conflict if attestations are disabled in configuration.
     * Returns 404 if the ledger entry does not exist or does not belong to the given WorkItem.
     *
     * @param workItemId the WorkItem UUID
     * @param entryId the ledger entry UUID to attest
     * @param request the attestation data
     * @return 201 Created on success, 404 if entry not found, 409 if attestations disabled
     */
    @POST
    @Path("/ledger/{entryId}/attestations")
    @Consumes(APPLICATION_JSON)
    @Transactional
    public Response postAttestation(@PathParam("id") final UUID workItemId,
            @PathParam("entryId") final UUID entryId,
            final LedgerAttestationRequest request) {
        if (!config.attestations().enabled()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("{\"error\":\"Attestations are disabled\"}")
                    .build();
        }

        // Verify the entry exists and belongs to this WorkItem
        final WorkItemLedgerEntry entry = ledgerRepo.findById(entryId)
                .filter(e -> e instanceof WorkItemLedgerEntry)
                .map(e -> (WorkItemLedgerEntry) e)
                .filter(e -> workItemId.equals(e.subjectId))
                .orElse(null);

        if (entry == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Ledger entry not found for this WorkItem\"}")
                    .build();
        }

        final LedgerAttestation attestation = new LedgerAttestation();
        attestation.ledgerEntryId = entryId;
        attestation.subjectId = workItemId;
        attestation.attestorId = request.attestorId();
        attestation.attestorType = request.attestorType();
        attestation.verdict = request.verdict();
        attestation.evidence = request.evidence();
        attestation.confidence = request.confidence();

        ledgerRepo.saveAttestation(attestation);

        return Response.status(Response.Status.CREATED).build();
    }
}
