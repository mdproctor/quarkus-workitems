package io.quarkiverse.workitems.ledger.service;

import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.ledger.runtime.config.LedgerConfig;
import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.model.LedgerMerkleFrontier;
import io.quarkiverse.ledger.runtime.model.supplement.ComplianceSupplement;
import io.quarkiverse.ledger.runtime.service.LedgerMerkleTree;
import io.quarkiverse.workitems.ledger.model.WorkItemLedgerEntry;
import io.quarkiverse.workitems.ledger.repository.WorkItemLedgerEntryRepository;
import io.quarkiverse.workitems.runtime.event.WorkItemLifecycleEvent;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.repository.WorkItemStore;

/**
 * CDI observer that writes a {@link WorkItemLedgerEntry} for every WorkItem lifecycle transition.
 *
 * <p>
 * This bean is the sole integration point between the core WorkItems extension and the ledger
 * module. The core fires {@link WorkItemLifecycleEvent} CDI events on every transition; this
 * observer captures them and appends an immutable record to the ledger. The core has no
 * knowledge of this observer — if the ledger module is absent, events fire into the void.
 *
 * <p>
 * The observer runs in the same transaction as the lifecycle event delivery. If the
 * enclosing transaction rolls back, the ledger entry is not written — preserving consistency.
 */
@ApplicationScoped
public class LedgerEventCapture {

    @Inject
    WorkItemLedgerEntryRepository ledgerRepo;

    @Inject
    WorkItemStore workItemStore;

    @Inject
    LedgerConfig config;

    /**
     * Observe a WorkItem lifecycle event and write the corresponding ledger entry.
     *
     * @param event the lifecycle event fired by {@code WorkItemService}
     */
    @Transactional
    void onWorkItemEvent(@Observes final WorkItemLifecycleEvent event) {
        if (!config.enabled()) {
            return;
        }

        // Load the WorkItem for the decisionContext snapshot
        final Optional<WorkItem> workItemOpt = workItemStore.get(event.workItemId());

        // Determine the next sequence number in this WorkItem's ledger
        final int seq = ledgerRepo.findLatestByWorkItemId(event.workItemId())
                .map(e -> e.sequenceNumber + 1)
                .orElse(1);

        final WorkItemLedgerEntry entry = new WorkItemLedgerEntry();
        entry.subjectId = event.workItemId();
        entry.sequenceNumber = seq;
        entry.entryType = LedgerEntryType.EVENT;
        entry.commandType = deriveCommandType(event.type());
        entry.eventType = deriveEventType(event.type());
        entry.actorId = event.actor();
        entry.actorType = deriveActorType(event.actor());
        entry.actorRole = deriveActorRole(event.type());
        // Set occurredAt explicitly with millis precision before hash computation —
        // @PrePersist sets it too late; DB truncates to millis so canonical form must match
        entry.occurredAt = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

        // Compliance supplement: rationale, planRef, detail, decisionContext
        final var compliance = new ComplianceSupplement();
        compliance.rationale = event.rationale();
        compliance.planRef = event.planRef();
        compliance.detail = event.detail();
        if (config.decisionContext().enabled() && workItemOpt.isPresent()) {
            compliance.decisionContext = buildDecisionContext(workItemOpt.get());
        }
        entry.attach(compliance);

        // Merkle leaf hash (chain integrity maintained by LedgerMerkleFrontier MMR)
        if (config.hashChain().enabled()) {
            entry.digest = LedgerMerkleTree.leafHash(entry);
        }

        ledgerRepo.save(entry);

        // Update Merkle Mountain Range frontier for this subject
        if (config.hashChain().enabled()) {
            final java.util.List<LedgerMerkleFrontier> current = LedgerMerkleFrontier.findBySubjectId(entry.subjectId);
            final java.util.List<LedgerMerkleFrontier> newFrontier = LedgerMerkleTree.append(entry.digest, current,
                    entry.subjectId);
            LedgerMerkleFrontier.delete("subjectId", entry.subjectId);
            newFrontier.forEach(n -> n.persist());
        }
    }

    /**
     * Snapshot the WorkItem's current state as a compact JSON string.
     *
     * @param wi the WorkItem to snapshot
     * @return a JSON object string containing status, priority, assigneeId, and expiresAt
     */
    private String buildDecisionContext(final WorkItem wi) {
        return String.format(
                "{\"status\":\"%s\",\"priority\":\"%s\",\"assigneeId\":%s,\"expiresAt\":%s}",
                wi.status,
                wi.priority,
                wi.assigneeId != null ? "\"" + wi.assigneeId + "\"" : "null",
                wi.expiresAt != null ? "\"" + wi.expiresAt + "\"" : "null");
    }

    /** Single source of truth for event suffix → (commandType, eventType, actorRole) mapping. */
    private static final Map<String, String[]> EVENT_META = Map.ofEntries(
            Map.entry("created", new String[] { "CreateWorkItem", "WorkItemCreated", "Initiator" }),
            Map.entry("assigned", new String[] { "ClaimWorkItem", "WorkItemAssigned", "Claimant" }),
            Map.entry("started", new String[] { "StartWorkItem", "WorkItemStarted", "Assignee" }),
            Map.entry("completed", new String[] { "CompleteWorkItem", "WorkItemCompleted", "Resolver" }),
            Map.entry("rejected", new String[] { "RejectWorkItem", "WorkItemRejected", "Resolver" }),
            Map.entry("delegated", new String[] { "DelegateWorkItem", "WorkItemDelegated", "Delegator" }),
            Map.entry("released", new String[] { "ReleaseWorkItem", "WorkItemReleased", "Assignee" }),
            Map.entry("suspended", new String[] { "SuspendWorkItem", "WorkItemSuspended", "Assignee" }),
            Map.entry("resumed", new String[] { "ResumeWorkItem", "WorkItemResumed", "Assignee" }),
            Map.entry("cancelled", new String[] { "CancelWorkItem", "WorkItemCancelled", "Administrator" }),
            Map.entry("expired", new String[] { "ExpireWorkItem", "WorkItemExpired", "System" }),
            Map.entry("escalated", new String[] { "EscalateWorkItem", "WorkItemEscalated", "System" }));

    private static final int META_COMMAND = 0;
    private static final int META_EVENT = 1;
    private static final int META_ROLE = 2;

    private String eventSuffix(final String eventType) {
        if (eventType == null)
            return null;
        final String[] parts = eventType.split("\\.");
        return parts[parts.length - 1];
    }

    private String deriveCommandType(final String eventType) {
        final String[] meta = EVENT_META.get(eventSuffix(eventType));
        return meta != null ? meta[META_COMMAND] : null;
    }

    private String deriveEventType(final String eventType) {
        final String[] meta = EVENT_META.get(eventSuffix(eventType));
        return meta != null ? meta[META_EVENT] : null;
    }

    private String deriveActorRole(final String eventType) {
        final String[] meta = EVENT_META.get(eventSuffix(eventType));
        return meta != null ? meta[META_ROLE] : "Assignee";
    }

    /**
     * Derive the actor type from the actorId prefix convention.
     *
     * <ul>
     * <li>{@code "agent:*"} → {@link ActorType#AGENT} — AI agents and automated classifiers</li>
     * <li>{@code "system:*"} → {@link ActorType#SYSTEM} — schedulers, expiry jobs, platform services</li>
     * <li>anything else → {@link ActorType#HUMAN} — human actors (default)</li>
     * </ul>
     *
     * @param actorId the raw actor identifier from the lifecycle event; may be {@code null}
     * @return the derived actor type; never {@code null}
     */
    private ActorType deriveActorType(final String actorId) {
        if (actorId == null) {
            return ActorType.HUMAN;
        }
        if (actorId.startsWith("agent:")) {
            return ActorType.AGENT;
        }
        if (actorId.startsWith("system:")) {
            return ActorType.SYSTEM;
        }
        return ActorType.HUMAN;
    }
}
