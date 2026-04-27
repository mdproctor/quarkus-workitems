package io.quarkiverse.work.examples.queues.review;

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

import io.quarkiverse.work.examples.queues.QueueScenarioResponse;
import io.quarkiverse.work.examples.queues.QueueScenarioStep;
import io.quarkiverse.work.examples.queues.lifecycle.QueueEventLog;
import io.quarkiverse.work.queues.model.FilterAction;
import io.quarkiverse.work.queues.model.FilterScope;
import io.quarkiverse.work.queues.model.QueueView;
import io.quarkiverse.work.queues.model.WorkItemFilter;
import io.quarkiverse.work.runtime.model.LabelPersistence;
import io.quarkiverse.work.runtime.model.WorkItem;
import io.quarkiverse.work.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.work.runtime.model.WorkItemPriority;
import io.quarkiverse.work.runtime.repository.WorkItemQuery;
import io.quarkiverse.work.runtime.repository.WorkItemStore;
import io.quarkiverse.work.runtime.service.WorkItemService;

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
 * {@code security-writers} is always urgent, regardless of its priority field.</li>
 * <li><b>JEXL</b> — {@code priority == 'CRITICAL'} → {@code review/urgent}</li>
 * <li><b>JEXL</b> — {@code priority == 'HIGH'} → {@code review/standard}</li>
 * <li><b>JEXL</b> — {@code priority == 'NORMAL' || priority == 'LOW'} → {@code review/routine}</li>
 * </ol>
 *
 * <h2>State sub-queues (cascade)</h2>
 * <p>
 * Three cascade filters fire after tier assignment, tracking where the document is in the
 * review workflow. Shown here for the {@code review/urgent} tier:
 * <ul>
 * <li>{@code labels.contains('review/urgent') && status == 'PENDING'}
 * → {@code review/urgent/unassigned}</li>
 * <li>{@code labels.contains('review/urgent') && status == 'ASSIGNED'}
 * → {@code review/urgent/claimed}</li>
 * <li>{@code labels.contains('review/urgent') && status == 'IN_PROGRESS'}
 * → {@code review/urgent/active}</li>
 * </ul>
 *
 * <h2>Queue lifecycle events per step</h2>
 * <ol>
 * <li>Security advisory (Lambda override) → ADDED to Urgent Reviews</li>
 * <li>Release notes (HIGH) → ADDED to Standard Reviews</li>
 * <li>Tutorial (NORMAL) → ADDED to Routine Reviews</li>
 * <li>Claim security advisory → CHANGED to Urgent Reviews (stays in queue, state label changes)</li>
 * <li>Start security advisory → CHANGED to Urgent Reviews</li>
 * <li>Queue snapshot → no queue events</li>
 * <li>Complete security advisory → REMOVED from Urgent Reviews (COMPLETED, filter guard exits)</li>
 * </ol>
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
    WorkItemStore workItemStore;

    @Inject
    QueueEventLog eventLog;

    @Transactional
    void setupFilters() {
        if (WorkItemFilter.count("name", "Review: Critical → Urgent Tier") > 0) {
            return;
        }

        final String notTerminal = "status != 'COMPLETED' && status != 'REJECTED' && status != 'CANCELLED' && status != 'ESCALATED'";

        persist("Review: Critical → Urgent Tier", "jexl",
                "priority == 'CRITICAL' && " + notTerminal,
                List.of(FilterAction.applyLabel("review/urgent")));

        persist("Review: High → Standard Tier", "jexl",
                "priority == 'HIGH' && " + notTerminal,
                List.of(FilterAction.applyLabel("review/standard")));

        persist("Review: Normal/Low → Routine Tier", "jexl",
                "(priority == 'NORMAL' || priority == 'LOW') && (candidateGroups == null || !candidateGroups.contains('security-writers')) && "
                        + notTerminal,
                List.of(FilterAction.applyLabel("review/routine")));

        persist("Review: Urgent + Pending → Unassigned", "jexl",
                "labels.contains('review/urgent') && status == 'PENDING'",
                List.of(FilterAction.applyLabel("review/urgent/unassigned")));

        persist("Review: Urgent + Assigned → Claimed", "jexl",
                "labels.contains('review/urgent') && status == 'ASSIGNED'",
                List.of(FilterAction.applyLabel("review/urgent/claimed")));

        persist("Review: Urgent + In-Progress → Active", "jexl",
                "labels.contains('review/urgent') && status == 'IN_PROGRESS'",
                List.of(FilterAction.applyLabel("review/urgent/active")));

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

    @Transactional
    void setupQueueViews() {
        if (QueueView.count("name", "Urgent Reviews") > 0)
            return;

        final QueueView urgent = new QueueView();
        urgent.name = "Urgent Reviews";
        urgent.labelPattern = "review/urgent";
        urgent.scope = FilterScope.ORG;
        urgent.persist();

        final QueueView standard = new QueueView();
        standard.name = "Standard Reviews";
        standard.labelPattern = "review/standard";
        standard.scope = FilterScope.ORG;
        standard.persist();

        final QueueView routine = new QueueView();
        routine.name = "Routine Reviews";
        routine.labelPattern = "review/routine";
        routine.scope = FilterScope.ORG;
        routine.persist();
    }

    /**
     * Run the document review pipeline scenario end to end.
     *
     * @param delayMs milliseconds to pause between steps for live dashboard observation.
     *        Defaults to 1500ms. Pass {@code ?delay=0} for instant execution (useful in tests).
     * @return scenario response with steps, queue events per step, and queue contents
     */
    @POST
    @Path("/run")
    public QueueScenarioResponse run(@QueryParam("delay") @DefaultValue("1500") long delayMs) {
        setupFilters();
        setupQueueViews();
        eventLog.clear();

        final List<QueueScenarioStep> steps = new ArrayList<>();

        // ── Step 1: Security advisory — NORMAL priority, overridden to urgent by Lambda ──
        LOG.info("[REVIEW] Step 1/7: Security advisory submitted — Lambda overrides NORMAL priority to urgent tier");
        final WorkItem secAdvisory = workItemService.create(new WorkItemCreateRequest(
                "Security advisory: TLS 1.0 deprecation — migration guide",
                "Update the TLS migration guide to reflect Q2 deprecation timeline.",
                "security-docs", "migration-guide", WorkItemPriority.NORMAL,
                null, "security-writers,docs-team", null, null, "doc-system",
                "{\"doc_type\": \"security-advisory\", \"publish_deadline\": \"2026-04-18\"}",
                null, null, null, null, null, null, null, null));
        steps.add(new QueueScenarioStep(1,
                "Security advisory — priority=NORMAL but candidateGroups contains 'security-writers'. " +
                        "Lambda filter overrides: review/urgent + review/urgent/unassigned",
                secAdvisory.id, inferredPaths(secAdvisory), manualPaths(secAdvisory),
                formatEvents(eventLog.drain())));
        sleep(delayMs);

        // ── Step 2: Release notes — HIGH priority → standard tier ──
        LOG.info("[REVIEW] Step 2/7: Release notes — HIGH priority → standard tier");
        final WorkItem releaseNotes = workItemService.create(new WorkItemCreateRequest(
                "v3.2 release notes — features and breaking changes",
                "Document all features and breaking changes for the v3.2 release.",
                "release-docs", "release-notes", WorkItemPriority.HIGH,
                null, "docs-team", null, null, "release-system",
                "{\"version\": \"3.2\", \"breaking_changes\": 3, \"new_features\": 12}",
                null, null, null, null, null, null, null, null));
        steps.add(new QueueScenarioStep(2,
                "Release notes — priority=HIGH → JEXL filter: review/standard + review/standard/unassigned",
                releaseNotes.id, inferredPaths(releaseNotes), manualPaths(releaseNotes),
                formatEvents(eventLog.drain())));
        sleep(delayMs);

        // ── Step 3: Tutorial — NORMAL priority → routine tier ──
        LOG.info("[REVIEW] Step 3/7: Tutorial — NORMAL priority → routine tier");
        final WorkItem tutorial = workItemService.create(new WorkItemCreateRequest(
                "Getting started tutorial — Quarkus WorkItems quick start",
                "Write a 10-minute getting-started guide for new users.",
                "tutorials", "quick-start", WorkItemPriority.NORMAL,
                null, "docs-team", null, null, "doc-system",
                "{\"estimated_reading_time_min\": 10, \"skill_level\": \"beginner\"}",
                null, null, null, null, null, null, null, null));
        steps.add(new QueueScenarioStep(3,
                "Tutorial — priority=NORMAL → JEXL filter: review/routine + review/routine/unassigned",
                tutorial.id, inferredPaths(tutorial), manualPaths(tutorial),
                formatEvents(eventLog.drain())));
        sleep(delayMs);

        // ── Step 4: Reviewer claims the security advisory ──
        LOG.info("[REVIEW] Step 4/7: Senior reviewer claims the security advisory");
        workItemService.claim(secAdvisory.id, "senior-reviewer");
        final WorkItem afterClaim = readFresh(secAdvisory.id);
        steps.add(new QueueScenarioStep(4,
                "Security advisory claimed (PENDING→ASSIGNED). " +
                        "Re-evaluation: review/urgent/unassigned removed, review/urgent/claimed applied. " +
                        "Queue event: CHANGED to Urgent Reviews (item stayed in queue, state label changed).",
                afterClaim.id, inferredPaths(afterClaim), manualPaths(afterClaim),
                formatEvents(eventLog.drain())));
        sleep(delayMs);

        // ── Step 5: Reviewer starts the advisory ──
        LOG.info("[REVIEW] Step 5/7: Reviewer starts reading the security advisory");
        workItemService.start(secAdvisory.id, "senior-reviewer");
        final WorkItem afterStart = readFresh(secAdvisory.id);
        steps.add(new QueueScenarioStep(5,
                "Security advisory started (ASSIGNED→IN_PROGRESS). " +
                        "Re-evaluation: review/urgent/claimed removed, review/urgent/active applied. " +
                        "Queue event: CHANGED to Urgent Reviews (item still in queue).",
                afterStart.id, inferredPaths(afterStart), manualPaths(afterStart),
                formatEvents(eventLog.drain())));
        sleep(delayMs);

        // ── Step 6: Snapshot — unassigned urgent queue ──
        LOG.info("[REVIEW] Step 6/7: Queue snapshot");
        final List<UUID> urgentUnassigned = workItemStore
                .scan(WorkItemQuery.byLabelPattern("review/urgent/unassigned"))
                .stream().map(w -> w.id).toList();
        final List<UUID> urgentActive = workItemStore
                .scan(WorkItemQuery.byLabelPattern("review/urgent/active"))
                .stream().map(w -> w.id).toList();
        final List<UUID> standardUnassigned = workItemStore
                .scan(WorkItemQuery.byLabelPattern("review/standard/unassigned"))
                .stream().map(w -> w.id).toList();
        final List<UUID> routineUnassigned = workItemStore
                .scan(WorkItemQuery.byLabelPattern("review/routine/unassigned"))
                .stream().map(w -> w.id).toList();
        steps.add(new QueueScenarioStep(6,
                "Queue snapshot: " +
                        "review/urgent/unassigned=" + urgentUnassigned.size() + " (advisory moved out), " +
                        "review/urgent/active=" + urgentActive.size() + " (advisory here), " +
                        "review/standard/unassigned=" + standardUnassigned.size() + " (release notes waiting), " +
                        "review/routine/unassigned=" + routineUnassigned.size() + " (tutorial waiting)",
                null,
                List.of(
                        "review/urgent/unassigned: " + urgentUnassigned.size(),
                        "review/urgent/active: " + urgentActive.size(),
                        "review/standard/unassigned: " + standardUnassigned.size(),
                        "review/routine/unassigned: " + routineUnassigned.size()),
                List.of(), List.of()));
        sleep(delayMs);

        // ── Step 7: Complete the advisory — leaves all review queues ──
        LOG.info("[REVIEW] Step 7/7: Security advisory approved — leaves all review queues");
        workItemService.complete(
                secAdvisory.id, "senior-reviewer",
                "{\"approved\": true, \"comments\": \"Accurate, well-structured. Ready to publish.\"}",
                "Content verified against latest NIST guidelines. No security issues found.",
                "DOC-REVIEW-POLICY-v1.4");
        final WorkItem afterComplete = readFresh(secAdvisory.id);
        steps.add(new QueueScenarioStep(7,
                "Security advisory completed (IN_PROGRESS→COMPLETED). " +
                        "Re-evaluation: notTerminal guard fails, all INFERRED labels removed. " +
                        "Queue event: REMOVED from Urgent Reviews.",
                afterComplete.id, inferredPaths(afterComplete), manualPaths(afterComplete),
                formatEvents(eventLog.drain())));

        return new QueueScenarioResponse(
                "document-review-pipeline",
                "A document flows through review/urgent/unassigned → claimed → active → (complete, REMOVED from queue). " +
                        "Lambda overrides NORMAL-priority security docs to urgent tier. " +
                        "Release notes and tutorial wait in their own tier queues unaffected.",
                steps, urgentActive);
    }

    private List<String> inferredPaths(final WorkItem wi) {
        return wi.labels.stream().filter(l -> l.persistence == LabelPersistence.INFERRED)
                .map(l -> l.path).sorted().toList();
    }

    private List<String> manualPaths(final WorkItem wi) {
        return wi.labels.stream().filter(l -> l.persistence == LabelPersistence.MANUAL)
                .map(l -> l.path).toList();
    }

    private List<String> formatEvents(final List<QueueEventLog.Entry> entries) {
        return entries.stream()
                .map(e -> e.eventType().name() + " to " + e.queueName())
                .toList();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    WorkItem readFresh(final UUID id) {
        return workItemStore.get(id).orElseThrow();
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
