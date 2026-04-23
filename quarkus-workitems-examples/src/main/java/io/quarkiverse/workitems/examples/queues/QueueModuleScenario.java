package io.quarkiverse.workitems.examples.queues;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.logging.Logger;

import io.quarkiverse.workitems.examples.StepLog;
import io.quarkiverse.workitems.queues.model.FilterScope;
import io.quarkiverse.workitems.queues.model.QueueView;
import io.quarkiverse.workitems.queues.model.WorkItemQueueState;
import io.quarkiverse.workitems.runtime.api.AuditEntryResponse;
import io.quarkiverse.workitems.runtime.api.WorkItemLabelResponse;
import io.quarkiverse.workitems.runtime.event.WorkItemLifecycleEvent;
import io.quarkiverse.workitems.runtime.model.AuditEntry;
import io.quarkiverse.workitems.runtime.model.LabelPersistence;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.workitems.runtime.model.WorkItemPriority;
import io.quarkiverse.workitems.runtime.repository.AuditEntryStore;
import io.quarkiverse.workitems.runtime.repository.WorkItemQuery;
import io.quarkiverse.workitems.runtime.repository.WorkItemStore;
import io.quarkiverse.workitems.runtime.service.WorkItemService;

/**
 * Scenario 8 — Queue Module: Label-Based Specialist Queues with Soft-Claim Handoff.
 *
 * <p>
 * Demonstrates the queue module's label-pattern queues and the relinquishable pickup
 * mechanism. A legal team uses specialist queues for contract-review, litigation, and
 * compliance work. Incoming WorkItems are labelled at creation; specialists pull from
 * their queue using "pickup". When a specialist is better suited, the current owner
 * marks the WorkItem relinquishable and a senior specialist takes over without any
 * force-reassignment.
 *
 * <p>
 * Steps:
 * <ol>
 * <li>Create WorkItem A with label {@code contract-review/nda}.</li>
 * <li>Create WorkItem B with label {@code compliance/gdpr}.</li>
 * <li>Create WorkItem C with label {@code contract-review/ip}.</li>
 * <li>Create a queue view for the {@code contract-review/*} pattern.</li>
 * <li>Query the queue — expect WorkItems A and C (both match the pattern).</li>
 * <li>{@code contract-specialist} picks up WorkItem A (standard claim from PENDING).</li>
 * <li>{@code contract-specialist} marks WorkItem A as relinquishable (handoff signal).</li>
 * <li>{@code senior-specialist} picks up WorkItem A (soft takeover).</li>
 * <li>{@code senior-specialist} starts and completes WorkItem A.</li>
 * </ol>
 *
 * <p>
 * Actors: {@code legal-system} (creator), {@code contract-specialist} (first claimant),
 * {@code senior-specialist} (final owner).
 *
 * <p>
 * Endpoint: {@code POST /examples/queues/run}
 */
@Path("/examples/queues")
@Produces(MediaType.APPLICATION_JSON)
public class QueueModuleScenario {

    private static final Logger LOG = Logger.getLogger(QueueModuleScenario.class);

    private static final String SCENARIO_ID = "queue-module-specialist-queues";
    private static final String ACTOR_CREATOR = "legal-system";
    private static final String ACTOR_CONTRACT = "contract-specialist";
    private static final String ACTOR_SENIOR = "senior-specialist";
    private static final String QUEUE_PATTERN = "contract-review/*";

    @Inject
    WorkItemService workItemService;

    @Inject
    WorkItemStore workItemStore;

    @Inject
    AuditEntryStore auditStore;

    @Inject
    Event<WorkItemLifecycleEvent> lifecycleEvent;

    /**
     * Run the queue module scenario end to end and return the queue size and final assignee.
     *
     * @return scenario response with queue contents, handoff result, and audit trail
     */
    @POST
    @Path("/run")
    @Transactional
    public QueueModuleResponse run() {
        final List<StepLog> steps = new ArrayList<>();
        final int total = 9;

        // Step 1: create WorkItem A — contract-review/nda
        final String description1 = "legal-system creates WorkItem A with label contract-review/nda";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 1, total, description1);

        final WorkItem wiA = workItemService.create(new WorkItemCreateRequest(
                "Review NDA with TechPartner Inc",
                "Non-disclosure agreement for technology partnership — standard NDA, review for compliance with IP policy",
                "legal",
                null,
                WorkItemPriority.NORMAL,
                null,
                "legal-team",
                null,
                "contract-review,nda",
                ACTOR_CREATOR,
                "{\"contractRef\": \"TECHPARTNER-NDA-2026-Q2\"}",
                null,
                null,
                null,
                List.of(new WorkItemLabelResponse("contract-review/nda", LabelPersistence.MANUAL, ACTOR_CREATOR)),
                null));
        steps.add(new StepLog(1, description1, wiA.id));

        // Step 2: create WorkItem B — compliance/gdpr
        final String description2 = "legal-system creates WorkItem B with label compliance/gdpr";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 2, total, description2);

        final WorkItem wiB = workItemService.create(new WorkItemCreateRequest(
                "GDPR data processing agreement — EU vendor",
                "Data Processing Agreement required for new EU-based data analytics vendor",
                "legal",
                null,
                WorkItemPriority.HIGH,
                null,
                "legal-team",
                null,
                "compliance,gdpr",
                ACTOR_CREATOR,
                "{\"contractRef\": \"EU-DPA-2026-0031\"}",
                null,
                null,
                null,
                List.of(new WorkItemLabelResponse("compliance/gdpr", LabelPersistence.MANUAL, ACTOR_CREATOR)),
                null));
        steps.add(new StepLog(2, description2, wiB.id));

        // Step 3: create WorkItem C — contract-review/ip
        final String description3 = "legal-system creates WorkItem C with label contract-review/ip";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 3, total, description3);

        final WorkItem wiC = workItemService.create(new WorkItemCreateRequest(
                "Review IP licensing agreement — ResearchCo",
                "Intellectual property licensing agreement covering patent and know-how transfer",
                "legal",
                null,
                WorkItemPriority.HIGH,
                null,
                "legal-team",
                null,
                "contract-review,ip-licensing",
                ACTOR_CREATOR,
                "{\"contractRef\": \"RESEARCHCO-IP-2026-Q2\"}",
                null,
                null,
                null,
                List.of(new WorkItemLabelResponse("contract-review/ip", LabelPersistence.MANUAL, ACTOR_CREATOR)),
                null));
        steps.add(new StepLog(3, description3, wiC.id));

        // Step 4: create the contract-review queue view
        final String description4 = "Create queue view for contract-review/* pattern";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 4, total, description4);

        final QueueView queueView = new QueueView();
        queueView.name = "Contract Review Queue";
        queueView.labelPattern = QUEUE_PATTERN;
        queueView.scope = FilterScope.TEAM;
        queueView.ownerId = null;
        queueView.sortField = "createdAt";
        queueView.sortDirection = "ASC";
        queueView.persist();
        final UUID queueId = queueView.id;
        steps.add(new StepLog(4, description4, null));

        // Step 5: query the queue — expect WorkItems A and C
        final String description5 = "Query contract-review/* queue — expect WorkItems A (nda) and C (ip)";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 5, total, description5);

        final List<WorkItem> queueContents = workItemStore.scan(WorkItemQuery.byLabelPattern(QUEUE_PATTERN));
        final int queueSize = queueContents.size();
        steps.add(new StepLog(5, String.format("%s — found %d items", description5, queueSize), null));

        // Step 6: contract-specialist picks up WorkItem A (standard claim from PENDING)
        final String description6 = "contract-specialist picks up WorkItem A from the contract-review queue";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 6, total, description6);
        workItemService.claim(wiA.id, ACTOR_CONTRACT);
        steps.add(new StepLog(6, description6, wiA.id));

        // Step 7: contract-specialist marks WorkItem A as relinquishable (handoff signal)
        final String description7 = "contract-specialist marks WorkItem A as relinquishable — needs senior review";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 7, total, description7);
        final WorkItemQueueState state = WorkItemQueueState.findOrCreate(wiA.id);
        state.relinquishable = true;
        steps.add(new StepLog(7, description7, wiA.id));

        // Step 8: senior-specialist picks up WorkItem A via soft takeover
        final String description8 = "senior-specialist picks up relinquishable WorkItem A (ownership transfer)";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 8, total, description8);
        final WorkItem wiARefreshed = workItemStore.get(wiA.id)
                .orElseThrow(() -> new IllegalStateException("WorkItem A not found: " + wiA.id));
        wiARefreshed.assigneeId = ACTOR_SENIOR;
        wiARefreshed.assignedAt = Instant.now();
        final WorkItem wiASaved = workItemStore.put(wiARefreshed);
        // Clear relinquishable flag — signal consumed
        state.relinquishable = false;
        lifecycleEvent.fire(WorkItemLifecycleEvent.of("ASSIGNED", wiASaved, ACTOR_SENIOR,
                "Queue pickup (relinquishable takeover)"));
        steps.add(new StepLog(8, description8, wiA.id));

        // Step 9: senior-specialist starts and completes WorkItem A
        final String description9 = "senior-specialist starts and completes WorkItem A";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 9, total, description9);
        workItemService.start(wiA.id, ACTOR_SENIOR);
        workItemService.complete(wiA.id, ACTOR_SENIOR,
                "{\"outcome\": \"APPROVED\", \"notes\": \"NDA reviewed by senior specialist — standard terms confirmed\"}");
        steps.add(new StepLog(9, description9, wiA.id));

        // Collect audit trail for WorkItem A (the focus of the handoff story)
        final List<AuditEntry> auditEntries = auditStore.findByWorkItemId(wiA.id);
        final List<AuditEntryResponse> auditTrail = auditEntries.stream()
                .map(a -> new AuditEntryResponse(a.id, a.event, a.actor, a.detail, a.occurredAt))
                .toList();

        // Determine final assignee from the refreshed WorkItem
        final String finalAssignee = workItemStore.get(wiA.id)
                .map(wi -> wi.assigneeId)
                .orElse(ACTOR_SENIOR);

        return new QueueModuleResponse(
                SCENARIO_ID,
                steps,
                queueId,
                queueSize,
                wiA.id,
                finalAssignee,
                auditTrail);
    }
}
