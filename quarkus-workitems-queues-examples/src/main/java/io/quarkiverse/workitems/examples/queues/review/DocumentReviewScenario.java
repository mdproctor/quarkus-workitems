package io.quarkiverse.workitems.examples.queues.review;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import org.jboss.logging.Logger;

import io.quarkiverse.workitems.examples.queues.QueueScenarioResponse;
import io.quarkiverse.workitems.examples.queues.QueueScenarioStep;
import io.quarkiverse.workitems.queues.model.FilterAction;
import io.quarkiverse.workitems.queues.model.FilterScope;
import io.quarkiverse.workitems.queues.model.WorkItemFilter;
import io.quarkiverse.workitems.runtime.model.LabelPersistence;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.workitems.runtime.model.WorkItemPriority;
import io.quarkiverse.workitems.runtime.repository.WorkItemRepository;
import io.quarkiverse.workitems.runtime.service.WorkItemService;

/**
 * Scenario: Document Review Pipeline — state-tracking queues.
 *
 * <p>
 * A technical writing team reviews documents before publication. Incoming documents
 * are routed to one of three review tiers and tracked through three workflow states.
 * Each WorkItem appears in exactly one state queue per tier at all times, moving
 * automatically as reviewers claim and start work.
 *
 * <h2>Tier assignment</h2>
 * <p>
 * Four filters decide which tier a document belongs to:
 * <ol>
 * <li><b>Lambda CDI bean ({@link SecurityWritersFilter})</b> — any document from
 * {@code security-writers} is always urgent, regardless of its priority field.
 * This is the Lambda pattern: a business rule that cannot be expressed as a simple
 * expression. A NORMAL-priority security advisory still gets {@code review/urgent}.</li>
 * <li><b>JEXL</b> — {@code priority == 'CRITICAL'} → {@code review/urgent}</li>
 * <li><b>JEXL</b> — {@code priority == 'HIGH'} → {@code review/standard}</li>
 * <li><b>JEXL</b> — {@code priority == 'NORMAL' || priority == 'LOW'} → {@code review/routine}</li>
 * </ol>
 *
 * <h2>State sub-queues (cascade)</h2>
 * <p>
 * Three cascade filters fire after tier assignment, creating sub-labels that track
 * where the document is in the review workflow. Shown here for the {@code review/urgent} tier:
 * <ul>
 * <li>{@code labels.contains('review/urgent') && status == 'PENDING'}
 * → {@code review/urgent/unassigned} — no reviewer has claimed it yet</li>
 * <li>{@code labels.contains('review/urgent') && status == 'ASSIGNED'}
 * → {@code review/urgent/claimed} — reviewer accepted it but hasn't started reading</li>
 * <li>{@code labels.contains('review/urgent') && status == 'IN_PROGRESS'}
 * → {@code review/urgent/active} — reviewer is actively reading and commenting</li>
 * </ul>
 *
 * <h2>The flow demonstrated</h2>
 * <p>
 * Three documents are created: a security advisory (NORMAL priority, overridden to urgent
 * by Lambda), a release note (HIGH → standard), and a tutorial (NORMAL → routine).
 * The security advisory is then walked through claim and start — visible in each
 * sub-queue in turn. The other two remain in their tier's unassigned queue.
 *
 * <h2>What makes this unique</h2>
 * <p>
 * The same WorkItem appears in {@code review/urgent/unassigned}, then
 * {@code review/urgent/claimed}, then {@code review/urgent/active} as lifecycle
 * events fire. The queue always reflects the current state — no manual queue
 * management, no staleness. This is INFERRED label re-evaluation in action.
 *
 * <p>
 * Endpoint: {@code POST /queue-examples/review/run}
 */
@Path("/queue-examples/review")
@Produces(MediaType.APPLICATION_JSON)
public class DocumentReviewScenario {

    private static final Logger LOG = Logger.getLogger(DocumentReviewScenario.class);

    @Inject
    WorkItemService workItemService;

    @Inject
    WorkItemRepository workItemRepo;

    @Transactional
    void setupFilters() {
        if (WorkItemFilter.count("name", "Review: Critical → Urgent Tier") > 0) {
            return;
        }

        // ── Tier assignment ──────────────────────────────────────────────────
        // Active-item guard shared by all tier filters:
        // status strings for terminal states — completed/rejected/cancelled/escalated items
        // should leave all queues, not be re-routed into a tier.
        final String notTerminal = "status != 'COMPLETED' && status != 'REJECTED' && status != 'CANCELLED' && status != 'ESCALATED'";

        // JEXL: CRITICAL priority → urgent (catches explicit critical docs)
        persist("Review: Critical → Urgent Tier", "jexl",
                "priority == 'CRITICAL' && " + notTerminal,
                List.of(FilterAction.applyLabel("review/urgent")));

        // JEXL: HIGH priority → standard
        persist("Review: High → Standard Tier", "jexl",
                "priority == 'HIGH' && " + notTerminal,
                List.of(FilterAction.applyLabel("review/standard")));

        // JEXL: NORMAL or LOW → routine (excludes security-writers — Lambda handles those)
        // The candidateGroups check makes this condition mutually exclusive with SecurityWritersFilter:
        // security-writers items get review/urgent from the Lambda regardless of priority,
        // so the routine filter must not also fire for them.
        persist("Review: Normal/Low → Routine Tier", "jexl",
                "(priority == 'NORMAL' || priority == 'LOW') && (candidateGroups == null || !candidateGroups.contains('security-writers')) && "
                        + notTerminal,
                List.of(FilterAction.applyLabel("review/routine")));

        // ── State sub-queues for urgent tier (cascade) ────────────────────────
        // These fire AFTER the tier filters, picking up the label they just applied.
        // Multi-pass evaluation ensures the cascade fires in the same re-evaluation cycle.

        persist("Review: Urgent + Pending → Unassigned", "jexl",
                "labels.contains('review/urgent') && status == 'PENDING'",
                List.of(FilterAction.applyLabel("review/urgent/unassigned")));

        persist("Review: Urgent + Assigned → Claimed", "jexl",
                "labels.contains('review/urgent') && status == 'ASSIGNED'",
                List.of(FilterAction.applyLabel("review/urgent/claimed")));

        persist("Review: Urgent + In-Progress → Active", "jexl",
                "labels.contains('review/urgent') && status == 'IN_PROGRESS'",
                List.of(FilterAction.applyLabel("review/urgent/active")));

        // ── State sub-queues for standard and routine tiers ──────────────────
        persist("Review: Standard + Pending → Unassigned", "jexl",
                "labels.contains('review/standard') && status == 'PENDING'",
                List.of(FilterAction.applyLabel("review/standard/unassigned")));

        persist("Review: Standard + Assigned → Claimed", "jexl",
                "labels.contains('review/standard') && status == 'ASSIGNED'",
                List.of(FilterAction.applyLabel("review/standard/claimed")));

        persist("Review: Standard + In-Progress → Active", "jexl",
                "labels.contains('review/standard') && status == 'IN_PROGRESS'",
                List.of(FilterAction.applyLabel("review/standard/active")));

        persist("Review: Routine + Pending → Unassigned", "jexl",
                "labels.contains('review/routine') && status == 'PENDING'",
                List.of(FilterAction.applyLabel("review/routine/unassigned")));

        persist("Review: Routine + Assigned → Claimed", "jexl",
                "labels.contains('review/routine') && status == 'ASSIGNED'",
                List.of(FilterAction.applyLabel("review/routine/claimed")));

        persist("Review: Routine + In-Progress → Active", "jexl",
                "labels.contains('review/routine') && status == 'IN_PROGRESS'",
                List.of(FilterAction.applyLabel("review/routine/active")));
    }

    /**
     * Run the document review pipeline scenario end to end.
     *
     * @param delayMs milliseconds to pause between steps so a live dashboard can observe each state transition.
     *        Defaults to 1500ms. Pass {@code ?delay=0} for instant execution (useful in tests).
     */
    @POST
    @Path("/run")
    public QueueScenarioResponse run(@QueryParam("delay") @DefaultValue("1500") long delayMs) {
        setupFilters();

        final List<QueueScenarioStep> steps = new ArrayList<>();

        // ── Step 1: Security advisory — NORMAL priority, overridden to urgent by Lambda ──
        LOG.info("[REVIEW] Step 1/7: Security advisory submitted — Lambda overrides NORMAL priority to urgent tier");
        final WorkItem secAdvisory = workItemService.create(new WorkItemCreateRequest(
                "Security advisory: TLS 1.0 deprecation — migration guide",
                "Update the TLS migration guide to reflect Q2 deprecation timeline. " +
                        "Must publish before customer notification goes out Friday.",
                "security-docs",
                "migration-guide",
                WorkItemPriority.NORMAL, // ← NORMAL, but Lambda overrides this
                null,
                "security-writers,docs-team", // ← triggers SecurityWritersFilter
                null, null,
                "doc-system",
                "{\"doc_type\": \"security-advisory\", \"publish_deadline\": \"2026-04-18\"}",
                null, null, null, null));

        steps.add(new QueueScenarioStep(1,
                "Security advisory — priority=NORMAL but candidateGroups contains 'security-writers'. " +
                        "Lambda filter (SecurityWritersFilter) overrides: review/urgent + review/urgent/unassigned",
                secAdvisory.id,
                inferredPaths(secAdvisory),
                manualPaths(secAdvisory)));
        sleep(delayMs);

        // ── Step 2: Release notes — HIGH priority → standard tier ──
        LOG.info("[REVIEW] Step 2/7: Release notes — HIGH priority → standard tier");
        final WorkItem releaseNotes = workItemService.create(new WorkItemCreateRequest(
                "v3.2 release notes — features and breaking changes",
                "Document all features and breaking changes for the v3.2 release. " +
                        "Target: shipped with the release build next Tuesday.",
                "release-docs",
                "release-notes",
                WorkItemPriority.HIGH,
                null,
                "docs-team",
                null, null,
                "release-system",
                "{\"version\": \"3.2\", \"breaking_changes\": 3, \"new_features\": 12}",
                null, null, null, null));

        steps.add(new QueueScenarioStep(2,
                "Release notes — priority=HIGH → JEXL filter: review/standard + review/standard/unassigned",
                releaseNotes.id,
                inferredPaths(releaseNotes),
                manualPaths(releaseNotes)));
        sleep(delayMs);

        // ── Step 3: Tutorial — NORMAL priority → routine tier ──
        LOG.info("[REVIEW] Step 3/7: Tutorial — NORMAL priority → routine tier");
        final WorkItem tutorial = workItemService.create(new WorkItemCreateRequest(
                "Getting started tutorial — Quarkus WorkItems quick start",
                "Write a 10-minute getting-started guide for new users. " +
                        "No deadline pressure; backlog item.",
                "tutorials",
                "quick-start",
                WorkItemPriority.NORMAL,
                null,
                "docs-team",
                null, null,
                "doc-system",
                "{\"estimated_reading_time_min\": 10, \"skill_level\": \"beginner\"}",
                null, null, null, null));

        steps.add(new QueueScenarioStep(3,
                "Tutorial — priority=NORMAL → JEXL filter: review/routine + review/routine/unassigned",
                tutorial.id,
                inferredPaths(tutorial),
                manualPaths(tutorial)));
        sleep(delayMs);

        // ── Step 4: Reviewer claims the security advisory ──
        LOG.info("[REVIEW] Step 4/7: Senior reviewer claims the security advisory");
        workItemService.claim(secAdvisory.id, "senior-reviewer");
        final WorkItem afterClaim = readFresh(secAdvisory.id);

        steps.add(new QueueScenarioStep(4,
                "Security advisory claimed by senior-reviewer (PENDING→ASSIGNED). " +
                        "Re-evaluation fires: review/urgent/unassigned removed, review/urgent/claimed applied. " +
                        "Tier label review/urgent stays. Document moves to 'claimed but not yet reading' queue.",
                afterClaim.id,
                inferredPaths(afterClaim),
                manualPaths(afterClaim)));
        sleep(delayMs);

        // ── Step 5: Reviewer starts the advisory ──
        LOG.info("[REVIEW] Step 5/7: Reviewer starts reading the security advisory");
        workItemService.start(secAdvisory.id, "senior-reviewer");
        final WorkItem afterStart = readFresh(secAdvisory.id);

        steps.add(new QueueScenarioStep(5,
                "Security advisory started (ASSIGNED→IN_PROGRESS). " +
                        "Re-evaluation fires: review/urgent/claimed removed, review/urgent/active applied. " +
                        "Document now in 'actively being reviewed' queue.",
                afterStart.id,
                inferredPaths(afterStart),
                manualPaths(afterStart)));
        sleep(delayMs);

        // ── Step 6: Snapshot — unassigned urgent queue ──
        LOG.info("[REVIEW] Step 6/7: Queue snapshot — review/urgent/unassigned (should be empty)");
        final List<UUID> urgentUnassigned = workItemRepo
                .findByLabelPattern("review/urgent/unassigned")
                .stream().map(w -> w.id).toList();
        final List<UUID> urgentActive = workItemRepo
                .findByLabelPattern("review/urgent/active")
                .stream().map(w -> w.id).toList();
        final List<UUID> standardUnassigned = workItemRepo
                .findByLabelPattern("review/standard/unassigned")
                .stream().map(w -> w.id).toList();
        final List<UUID> routineUnassigned = workItemRepo
                .findByLabelPattern("review/routine/unassigned")
                .stream().map(w -> w.id).toList();

        steps.add(new QueueScenarioStep(6,
                "Queue snapshot: " +
                        "review/urgent/unassigned=" + urgentUnassigned.size() + " (security advisory moved out), " +
                        "review/urgent/active=" + urgentActive.size() + " (security advisory here), " +
                        "review/standard/unassigned=" + standardUnassigned.size() + " (release notes waiting), " +
                        "review/routine/unassigned=" + routineUnassigned.size() + " (tutorial waiting)",
                null,
                List.of(
                        "review/urgent/unassigned: " + urgentUnassigned.size(),
                        "review/urgent/active: " + urgentActive.size(),
                        "review/standard/unassigned: " + standardUnassigned.size(),
                        "review/routine/unassigned: " + routineUnassigned.size()),
                List.of()));
        sleep(delayMs);

        // ── Step 7: Complete the advisory — leaves all review queues ──
        LOG.info("[REVIEW] Step 7/7: Security advisory approved — leaves all review queues");
        workItemService.complete(
                secAdvisory.id,
                "senior-reviewer",
                "{\"approved\": true, \"comments\": \"Accurate, well-structured. Ready to publish.\"}",
                "Content verified against latest NIST guidelines. No security issues found.",
                "DOC-REVIEW-POLICY-v1.4");
        final WorkItem afterComplete = readFresh(secAdvisory.id);

        steps.add(new QueueScenarioStep(7,
                "Security advisory completed (IN_PROGRESS→COMPLETED). " +
                        "Re-evaluation fires: status is no longer PENDING/ASSIGNED/IN_PROGRESS so none of the " +
                        "state filters match. All INFERRED labels removed — document has left every review queue.",
                afterComplete.id,
                inferredPaths(afterComplete),
                manualPaths(afterComplete)));

        // Return the urgent active queue as the primary result (the advisory's final active state was here)
        return new QueueScenarioResponse(
                "document-review-pipeline",
                "A document flows through review/urgent/unassigned → claimed → active → (complete, leaves all queues). " +
                        "Lambda overrides NORMAL-priority security docs to urgent tier. " +
                        "Release notes and tutorial wait in their own tier queues unaffected.",
                steps,
                urgentActive);
    }

    private List<String> inferredPaths(final WorkItem wi) {
        return wi.labels.stream()
                .filter(l -> l.persistence == LabelPersistence.INFERRED)
                .map(l -> l.path)
                .sorted()
                .toList();
    }

    private List<String> manualPaths(final WorkItem wi) {
        return wi.labels.stream()
                .filter(l -> l.persistence == LabelPersistence.MANUAL)
                .map(l -> l.path)
                .toList();
    }

    /**
     * Reads a WorkItem in a dedicated REQUIRES_NEW transaction so the caller always
     * gets the most recently committed state, bypassing any stale request-scoped
     * Hibernate session first-level cache.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    WorkItem readFresh(final UUID id) {
        return workItemRepo.findById(id).orElseThrow();
    }

    private static void sleep(final long delayMs) {
        if (delayMs <= 0)
            return;
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void persist(final String name, final String lang, final String expr,
            final List<FilterAction> actions) {
        final WorkItemFilter f = new WorkItemFilter();
        f.name = name;
        f.scope = FilterScope.ORG;
        f.conditionLanguage = lang;
        f.conditionExpression = expr;
        f.actions = WorkItemFilter.serializeActions(actions);
        f.active = true;
        f.persist();
    }
}
