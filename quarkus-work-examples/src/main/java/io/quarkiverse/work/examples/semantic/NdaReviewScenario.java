package io.quarkiverse.work.examples.semantic;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.logging.Logger;

import io.quarkiverse.work.ai.skill.WorkerSkillProfile;
import io.quarkiverse.work.examples.StepLog;
import io.quarkiverse.work.runtime.api.AuditEntryResponse;
import io.quarkiverse.work.runtime.model.AuditEntry;
import io.quarkiverse.work.runtime.model.WorkItem;
import io.quarkiverse.work.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.work.runtime.model.WorkItemPriority;
import io.quarkiverse.work.runtime.model.WorkItemStatus;
import io.quarkiverse.work.runtime.repository.AuditEntryStore;
import io.quarkiverse.work.runtime.service.WorkItemService;

/**
 * Scenario 5 — Semantic Skill Routing: NDA Review.
 *
 * <p>
 * Demonstrates that {@code SemanticWorkerSelectionStrategy} routes a WorkItem
 * to the most qualified candidate without any explicit pre-assignment.
 *
 * <p>
 * Story: An AI agent raises an NDA review task. Two candidates are eligible —
 * a legal specialist and a finance analyst. The routing engine scores each
 * candidate's skill profile against the work item description and pre-assigns
 * to the legal specialist, who has the right background.
 *
 * <p>
 * The {@link KeywordSkillMatcher} active in this module provides deterministic
 * scoring (keyword overlap) so the scenario runs headlessly without a real
 * embedding model.
 *
 * <p>
 * Actors:
 * <ul>
 * <li>{@code contract-agent} — AI agent that raises the NDA review task</li>
 * <li>{@code legal-specialist} — candidate with legal/NDA expertise (routed to)</li>
 * <li>{@code finance-analyst} — candidate with finance expertise (not selected)</li>
 * </ul>
 *
 * <p>
 * Endpoint: {@code POST /examples/semantic/run}
 */
@Path("/examples/semantic")
@Produces(MediaType.APPLICATION_JSON)
public class NdaReviewScenario {

    private static final Logger LOG = Logger.getLogger(NdaReviewScenario.class);

    private static final String SCENARIO_ID = "nda-review-semantic-routing";
    private static final String ACTOR_AGENT = "contract-agent";
    private static final String ACTOR_LEGAL = "legal-specialist";
    private static final String ACTOR_FINANCE = "finance-analyst";

    @Inject
    WorkItemService workItemService;

    @Inject
    AuditEntryStore auditStore;

    /**
     * Run the NDA review semantic routing scenario from end to end.
     *
     * @return scenario response showing the routing decision and outcome
     */
    @POST
    @Path("/run")
    @Transactional
    public SemanticRoutingResponse run() {
        final List<StepLog> steps = new ArrayList<>();
        final int total = 5;

        // Step 1: seed skill profiles for both candidates
        final String description1 = "Seed skill profiles for legal-specialist and finance-analyst";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 1, total, description1);
        upsertProfile(ACTOR_LEGAL,
                "Expert in NDA review, contract negotiation, intellectual property law, and legal compliance");
        upsertProfile(ACTOR_FINANCE,
                "Specialist in financial analysis, budgeting, expense reports, and accounting reviews");
        steps.add(new StepLog(1, description1, null));

        // Step 2: contract-agent raises the NDA review task
        final String description2 = "contract-agent creates NDA review WorkItem for Acme Corp partnership";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 2, total, description2);

        final WorkItemCreateRequest request = new WorkItemCreateRequest(
                "Review NDA with Acme Corp",
                "Non-disclosure agreement for IP protection and legal compliance with Acme Corp partnership",
                "legal",
                null,
                WorkItemPriority.HIGH,
                null,
                null,
                ACTOR_LEGAL + "," + ACTOR_FINANCE,
                null,
                ACTOR_AGENT,
                "{\"contractRef\": \"ACME-NDA-2026-04\"}",
                null,
                null,
                null,
                null,
                0.92,
                null, null, null);

        final WorkItem wi = workItemService.create(request);
        steps.add(new StepLog(2, description2, wi.id));

        // Step 3: verify routing — legal-specialist should be pre-assigned
        final String description3 = String.format(
                "Semantic routing selected '%s' (keyword match: NDA, legal, compliance, review)",
                wi.assigneeId);
        LOG.infof("[SCENARIO] Step %d/%d: %s", 3, total, description3);
        if (wi.status != WorkItemStatus.ASSIGNED || !ACTOR_LEGAL.equals(wi.assigneeId)) {
            throw new IllegalStateException(
                    "Expected semantic routing to assign legal-specialist but got: assigneeId="
                            + wi.assigneeId + ", status=" + wi.status);
        }
        steps.add(new StepLog(3, description3, wi.id));

        // Step 4: legal-specialist starts the WorkItem (already ASSIGNED — no claim needed)
        final String description4 = "legal-specialist starts the NDA review";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 4, total, description4);
        workItemService.start(wi.id, ACTOR_LEGAL);
        steps.add(new StepLog(4, description4, wi.id));

        // Step 5: legal-specialist completes with resolution
        final String description5 = "legal-specialist completes NDA review — standard terms accepted";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 5, total, description5);
        workItemService.complete(
                wi.id,
                ACTOR_LEGAL,
                "{\"outcome\": \"APPROVED\", \"notes\": \"Standard NDA terms; no amendments required\"}",
                "NDA reviewed against IP protection checklist — all clauses compliant",
                "LEGAL-NDA-POLICY-v3.0");
        steps.add(new StepLog(5, description5, wi.id));

        final List<AuditEntry> auditEntries = auditStore.findByWorkItemId(wi.id);
        final List<AuditEntryResponse> auditTrail = auditEntries.stream()
                .map(a -> new AuditEntryResponse(a.id, a.event, a.actor, a.detail, a.occurredAt))
                .toList();

        return new SemanticRoutingResponse(SCENARIO_ID, steps, wi.id, wi.assigneeId, ACTOR_LEGAL, auditTrail);
    }

    private void upsertProfile(final String workerId, final String narrative) {
        final WorkerSkillProfile existing = WorkerSkillProfile.findById(workerId);
        if (existing == null) {
            final var profile = new WorkerSkillProfile();
            profile.workerId = workerId;
            profile.narrative = narrative;
            profile.persist();
        } else {
            existing.narrative = narrative;
        }
    }
}
