package io.quarkiverse.workitems.examples.lowconfidence;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.logging.Logger;

import io.quarkiverse.workitems.examples.StepLog;
import io.quarkiverse.workitems.runtime.api.AuditEntryResponse;
import io.quarkiverse.workitems.runtime.model.AuditEntry;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.workitems.runtime.model.WorkItemPriority;
import io.quarkiverse.workitems.runtime.repository.AuditEntryStore;
import io.quarkiverse.workitems.runtime.service.WorkItemService;

/**
 * Scenario 7 — Low Confidence AI Filter: Flagging Uncertain Contract Reviews.
 *
 * <p>
 * Demonstrates the {@code ai/low-confidence} permanent filter definition produced by
 * {@code LowConfidenceFilterProducer}. An AI orchestration system routes contract review
 * requests to WorkItems. Requests generated with high confidence (≥ 0.7) pass through
 * cleanly; uncertain requests (confidence &lt; 0.7) are automatically labelled
 * {@code ai/low-confidence} so human reviewers apply extra scrutiny. WorkItems with no
 * confidence score (manual creation) are also unaffected.
 *
 * <p>
 * Steps:
 * <ol>
 * <li>AI agent creates a HIGH-confidence WorkItem (0.95) — no flag applied.</li>
 * <li>AI agent creates a LOW-confidence WorkItem (0.45) — {@code ai/low-confidence} label applied.</li>
 * <li>AI agent creates a NULL-confidence WorkItem (manual) — no flag applied.</li>
 * <li>Verify labels on all three WorkItems.</li>
 * <li>Complete WorkItems 1 and 2.</li>
 * </ol>
 *
 * <p>
 * Actors: {@code contract-ai} (AI agent), {@code assistant} (manual creator),
 * {@code contract-reviewer} (completer).
 *
 * <p>
 * Endpoint: {@code POST /examples/lowconfidence/run}
 */
@Path("/examples/lowconfidence")
@Produces(MediaType.APPLICATION_JSON)
public class LowConfidenceScenario {

    private static final Logger LOG = Logger.getLogger(LowConfidenceScenario.class);

    private static final String SCENARIO_ID = "low-confidence-ai-filter";
    private static final String ACTOR_AI = "contract-ai";
    private static final String ACTOR_MANUAL = "assistant";
    private static final String ACTOR_REVIEWER = "contract-reviewer";
    private static final String LABEL_LOW_CONFIDENCE = "ai/low-confidence";

    @Inject
    WorkItemService workItemService;

    @Inject
    AuditEntryStore auditStore;

    /**
     * Run the low-confidence AI filter scenario end to end and return label verification results.
     *
     * @return scenario response with per-item flag outcomes and combined audit trail
     */
    @POST
    @Path("/run")
    @Transactional
    public LowConfidenceResponse run() {
        final List<StepLog> steps = new ArrayList<>();
        final int total = 5;

        // Step 1: AI agent creates a HIGH-confidence WorkItem (0.95) — should NOT be flagged
        final String description1 = "contract-ai creates WorkItem with confidenceScore=0.95 (high confidence — no flag expected)";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 1, total, description1);

        final WorkItemCreateRequest highConfidenceRequest = new WorkItemCreateRequest(
                "Review standard NDA — Acme Corp",
                "Standard non-disclosure agreement for Acme Corp partnership — straightforward NDA with no unusual clauses",
                "contract-review",
                null,
                WorkItemPriority.NORMAL,
                null,
                "legal-team",
                null,
                "contract-review,nda",
                ACTOR_AI,
                "{\"contractRef\": \"ACME-NDA-2026-04\", \"contractType\": \"NDA\"}",
                null,
                null,
                null,
                null,
                0.95);

        final WorkItem highConfidenceWi = workItemService.create(highConfidenceRequest);
        steps.add(new StepLog(1, description1, highConfidenceWi.id));

        // Step 2: AI agent creates a LOW-confidence WorkItem (0.45) — should be flagged
        final String description2 = "contract-ai creates WorkItem with confidenceScore=0.45 (low confidence — ai/low-confidence label expected)";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 2, total, description2);

        final WorkItemCreateRequest lowConfidenceRequest = new WorkItemCreateRequest(
                "Review unusual IP licensing clause",
                "IP licensing clause appears to grant unusually broad rights — may involve novel legal territory requiring expert judgment",
                "contract-review",
                null,
                WorkItemPriority.HIGH,
                null,
                "legal-team",
                null,
                "contract-review,ip-licensing",
                ACTOR_AI,
                "{\"contractRef\": \"PARTNER-IP-2026-Q2\", \"contractType\": \"IP_LICENSE\", \"flagReason\": \"unusual_scope\"}",
                null,
                null,
                null,
                null,
                0.45);

        final WorkItem lowConfidenceWi = workItemService.create(lowConfidenceRequest);
        steps.add(new StepLog(2, description2, lowConfidenceWi.id));

        // Step 3: manual request with null confidence — should NOT be flagged
        final String description3 = "assistant creates WorkItem with no confidence score (manual request — no flag expected)";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 3, total, description3);

        final WorkItemCreateRequest nullConfidenceRequest = new WorkItemCreateRequest(
                "Manual contract review request",
                "Legal team has requested a manual review of the supplier agreement — submitted by the legal assistant",
                "contract-review",
                null,
                WorkItemPriority.NORMAL,
                null,
                "legal-team",
                null,
                "contract-review",
                ACTOR_MANUAL,
                "{\"requestRef\": \"MANUAL-2026-0042\"}",
                null,
                null,
                null,
                null,
                null);

        final WorkItem nullConfidenceWi = workItemService.create(nullConfidenceRequest);
        steps.add(new StepLog(3, description3, nullConfidenceWi.id));

        // Step 4: verify labels
        final String description4 = String.format(
                "Verify labels: highConfidence=%s, lowConfidence=%s, nullConfidence=%s",
                hasLabel(highConfidenceWi, LABEL_LOW_CONFIDENCE) ? "FLAGGED" : "clean",
                hasLabel(lowConfidenceWi, LABEL_LOW_CONFIDENCE) ? "FLAGGED" : "clean",
                hasLabel(nullConfidenceWi, LABEL_LOW_CONFIDENCE) ? "FLAGGED" : "clean");
        LOG.infof("[SCENARIO] Step %d/%d: %s", 4, total, description4);

        final boolean highConfidenceClean = !hasLabel(highConfidenceWi, LABEL_LOW_CONFIDENCE);
        final boolean lowConfidenceFlagged = hasLabel(lowConfidenceWi, LABEL_LOW_CONFIDENCE);
        final boolean nullConfidenceClean = !hasLabel(nullConfidenceWi, LABEL_LOW_CONFIDENCE);

        steps.add(new StepLog(4, description4, null));

        // Step 5: complete WorkItems 1 and 2 — reviewer processes high-confidence directly,
        // flags the low-confidence for extra scrutiny before completing
        final String description5 = "contract-reviewer completes high-confidence and low-confidence WorkItems";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 5, total, description5);

        workItemService.claim(highConfidenceWi.id, ACTOR_REVIEWER);
        workItemService.start(highConfidenceWi.id, ACTOR_REVIEWER);
        workItemService.complete(highConfidenceWi.id, ACTOR_REVIEWER,
                "{\"outcome\": \"APPROVED\", \"notes\": \"Standard NDA — no amendments required\"}");

        workItemService.claim(lowConfidenceWi.id, ACTOR_REVIEWER);
        workItemService.start(lowConfidenceWi.id, ACTOR_REVIEWER);
        workItemService.complete(lowConfidenceWi.id, ACTOR_REVIEWER,
                "{\"outcome\": \"APPROVED_WITH_AMENDMENTS\", \"notes\": \"IP clause narrowed — extra scrutiny applied due to ai/low-confidence flag\"}");

        steps.add(new StepLog(5, description5, null));

        // Collect audit trail across all three WorkItems
        final List<AuditEntryResponse> auditTrail = new ArrayList<>();
        for (final WorkItem wi : List.of(highConfidenceWi, lowConfidenceWi, nullConfidenceWi)) {
            final List<AuditEntry> auditEntries = auditStore.findByWorkItemId(wi.id);
            auditEntries.stream()
                    .map(a -> new AuditEntryResponse(a.id, a.event, a.actor, a.detail, a.occurredAt))
                    .forEach(auditTrail::add);
        }

        return new LowConfidenceResponse(
                SCENARIO_ID,
                steps,
                highConfidenceWi.id,
                lowConfidenceWi.id,
                nullConfidenceWi.id,
                highConfidenceClean,
                lowConfidenceFlagged,
                nullConfidenceClean,
                auditTrail);
    }

    private boolean hasLabel(final WorkItem wi, final String labelPath) {
        return wi.labels != null && wi.labels.stream().anyMatch(l -> labelPath.equals(l.path));
    }
}
