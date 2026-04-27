package io.quarkiverse.work.dashboard;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.work.queues.model.FilterAction;
import io.quarkiverse.work.queues.model.FilterScope;
import io.quarkiverse.work.queues.model.WorkItemFilter;
import io.quarkiverse.work.queues.service.WorkItemFilterBean;
import io.quarkiverse.work.runtime.model.WorkItem;
import io.quarkiverse.work.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.work.runtime.model.WorkItemPriority;
import io.quarkiverse.work.runtime.repository.WorkItemStore;
import io.quarkiverse.work.runtime.service.WorkItemService;

/**
 * Stateful CDI bean managing the document review demo scenario step by step.
 *
 * <p>
 * Each call to {@link #advance()} executes the next step and returns a description.
 * State persists across calls within the same application instance.
 */
@ApplicationScoped
public class ReviewStepService {

    @Inject
    WorkItemService workItemService;

    @Inject
    WorkItemStore workItemStore;

    @Inject
    Instance<WorkItemFilterBean> lambdaFilterBeans;

    private final AtomicInteger step = new AtomicInteger(0);
    private final AtomicReference<UUID> advisoryId = new AtomicReference<>();
    private final AtomicReference<UUID> releaseNotesId = new AtomicReference<>();
    private final AtomicReference<UUID> tutorialId = new AtomicReference<>();

    /**
     * Result record returned by each step.
     *
     * @param step the step index after the operation
     * @param action short description of the action taken
     * @param detail longer diagnostic detail
     * @param hints list of contextual hints for the user
     */
    public record StepResult(int step, String action, String detail, List<String> hints) {
    }

    /** How many Lambda filter beans are currently discovered by CDI. */
    public int lambdaFilterCount() {
        int count = 0;
        for (@SuppressWarnings("unused")
        final WorkItemFilterBean b : lambdaFilterBeans) {
            count++;
        }
        return count;
    }

    /** Names of discovered Lambda filter beans — for diagnostics. */
    public List<String> lambdaFilterNames() {
        final List<String> names = new ArrayList<>();
        lambdaFilterBeans.forEach(b -> names.add(b.getClass().getSimpleName()));
        return names;
    }

    /** Current step index (0 = not started). */
    public int currentStep() {
        return step.get();
    }

    /** Human-readable description of the next action. */
    public String nextAction() {
        return switch (step.get()) {
            case 0 -> "Press 's': create 3 documents + setup filters";
            case 1 -> "Press 's': claim the security advisory";
            case 2 -> "Press 's': start the security advisory";
            case 3 -> "Press 's': complete the security advisory";
            case 4 -> "Press 's': reset — clear all review WorkItems";
            default -> "Unknown step — press 's' to reset";
        };
    }

    /** Advisory WorkItem ID (null before step 1). */
    public UUID getAdvisoryId() {
        return advisoryId.get();
    }

    /** Advance one step. */
    @Transactional
    public StepResult advance() {
        return switch (step.get()) {
            case 0 -> stepSetup();
            case 1 -> stepClaim();
            case 2 -> stepStart();
            case 3 -> stepComplete();
            default -> stepReset();
        };
    }

    /** Reset to step 0. */
    @Transactional
    public StepResult reset() {
        return stepReset();
    }

    // ── Steps ────────────────────────────────────────────────────────────────

    private StepResult stepSetup() {
        ensureFilters();

        final WorkItem advisory = workItemService.create(new WorkItemCreateRequest(
                "Security advisory: TLS 1.0 deprecation — migration guide",
                "Update TLS guide. Must publish before customer notification Friday.",
                "security-docs", "migration-guide", WorkItemPriority.NORMAL,
                null, "security-writers,docs-team", null, null, "doc-system",
                "{\"doc_type\": \"security-advisory\"}", null, null, null, null, null, null, null, null));
        advisoryId.set(advisory.id);

        final WorkItem releaseNotes = workItemService.create(new WorkItemCreateRequest(
                "v3.2 release notes — features and breaking changes",
                "Document all features and breaking changes for the v3.2 release.",
                "release-docs", "release-notes", WorkItemPriority.HIGH,
                null, "docs-team", null, null, "release-system",
                null, null, null, null, null, null, null, null, null));
        releaseNotesId.set(releaseNotes.id);

        final WorkItem tutorial = workItemService.create(new WorkItemCreateRequest(
                "Getting started tutorial — Quarkus WorkItems quick start",
                "Write a 10-minute getting-started guide for new users.",
                "tutorials", "quick-start", WorkItemPriority.NORMAL,
                null, "docs-team", null, null, "doc-system",
                null, null, null, null, null, null, null, null, null));
        tutorialId.set(tutorial.id);

        step.set(1);

        final WorkItem fresh = readFresh(advisory.id);
        final List<String> labels = fresh.labels.stream().map(l -> l.path).sorted().toList();
        final List<String> lambdaNames = lambdaFilterNames();

        return new StepResult(1,
                "Created 3 documents",
                "Lambda filters: " + lambdaNames + ". Advisory labels: " + labels,
                List.of("Urgent row should show advisory in Unassigned column",
                        "If Urgent is empty, SecurityWritersFilter is not discovered",
                        "Next: press 's' to claim the security advisory"));
    }

    private StepResult stepClaim() {
        final UUID id = advisoryId.get();
        if (id == null) {
            step.set(0);
            return new StepResult(0, "No advisory", "Run setup first (step 0)", List.of());
        }
        workItemService.claim(id, "senior-reviewer");
        step.set(2);
        final WorkItem fresh = readFresh(id);
        final List<String> labels = fresh.labels.stream().map(l -> l.path).sorted().toList();
        return new StepResult(2,
                "Claimed security advisory",
                "senior-reviewer claimed it. Status: ASSIGNED. Labels: " + labels,
                List.of("Advisory should move: Unassigned → Claimed column",
                        "Next: press 's' to start work"));
    }

    private StepResult stepStart() {
        final UUID id = advisoryId.get();
        if (id == null) {
            step.set(0);
            return new StepResult(0, "No advisory", "Run setup first (step 0)", List.of());
        }
        workItemService.start(id, "senior-reviewer");
        step.set(3);
        final WorkItem fresh = readFresh(id);
        final List<String> labels = fresh.labels.stream().map(l -> l.path).sorted().toList();
        return new StepResult(3,
                "Started reviewing advisory",
                "Status: IN_PROGRESS. Labels: " + labels,
                List.of("Advisory should move: Claimed → Active column",
                        "Next: press 's' to complete"));
    }

    private StepResult stepComplete() {
        final UUID id = advisoryId.get();
        if (id == null) {
            step.set(0);
            return new StepResult(0, "No advisory", "Run setup first (step 0)", List.of());
        }
        workItemService.complete(id, "senior-reviewer",
                "{\"approved\": true}",
                "Content verified. Ready to publish.",
                "DOC-REVIEW-POLICY-v1.4");
        step.set(4);
        final WorkItem fresh = readFresh(id);
        final List<String> labels = fresh.labels.stream().map(l -> l.path).sorted().toList();
        return new StepResult(4,
                "Completed security advisory",
                "Status: COMPLETED. Labels: " + labels + " (all INFERRED labels cleared)",
                List.of("Advisory should disappear from all review queues",
                        "Release notes and tutorial remain in their queues",
                        "Next: press 's' to reset"));
    }

    private StepResult stepReset() {
        final List<UUID> ids = new ArrayList<>();
        if (advisoryId.get() != null) {
            ids.add(advisoryId.get());
        }
        if (releaseNotesId.get() != null) {
            ids.add(releaseNotesId.get());
        }
        if (tutorialId.get() != null) {
            ids.add(tutorialId.get());
        }
        for (final UUID id : ids) {
            if (id != null) {
                workItemStore.get(id).ifPresent(wi -> {
                    if (!wi.status.isTerminal()) {
                        workItemService.cancel(wi.id, "dashboard-reset", "Dashboard reset");
                    }
                });
            }
        }
        advisoryId.set(null);
        releaseNotesId.set(null);
        tutorialId.set(null);
        step.set(0);
        return new StepResult(0,
                "Reset complete",
                "All review WorkItems cancelled. Filters preserved for next run.",
                List.of("Press 's' to create 3 new documents and begin again"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    WorkItem readFresh(final UUID id) {
        return workItemStore.get(id).orElseThrow();
    }

    @Transactional
    void ensureFilters() {
        if (WorkItemFilter.count("name", "Review: Critical → Urgent Tier") > 0) {
            return;
        }
        final String notTerminal = "status != 'COMPLETED' && status != 'REJECTED' && status != 'CANCELLED' && status != 'ESCALATED'";
        persist("Review: Critical \u2192 Urgent Tier", "jexl",
                "priority == 'CRITICAL' && " + notTerminal,
                List.of(FilterAction.applyLabel("review/urgent")));
        persist("Review: High \u2192 Standard Tier", "jexl",
                "priority == 'HIGH' && " + notTerminal,
                List.of(FilterAction.applyLabel("review/standard")));
        persist("Review: Normal/Low \u2192 Routine Tier", "jexl",
                "(priority == 'NORMAL' || priority == 'LOW') && (candidateGroups == null || !candidateGroups.contains('security-writers')) && "
                        + notTerminal,
                List.of(FilterAction.applyLabel("review/routine")));
        persist("Review: Urgent + Pending \u2192 Unassigned", "jexl",
                "labels.contains('review/urgent') && status == 'PENDING'",
                List.of(FilterAction.applyLabel("review/urgent/unassigned")));
        persist("Review: Urgent + Assigned \u2192 Claimed", "jexl",
                "labels.contains('review/urgent') && status == 'ASSIGNED'",
                List.of(FilterAction.applyLabel("review/urgent/claimed")));
        persist("Review: Urgent + In-Progress \u2192 Active", "jexl",
                "labels.contains('review/urgent') && status == 'IN_PROGRESS'",
                List.of(FilterAction.applyLabel("review/urgent/active")));
        persist("Review: Standard + Pending \u2192 Unassigned", "jexl",
                "labels.contains('review/standard') && status == 'PENDING'",
                List.of(FilterAction.applyLabel("review/standard/unassigned")));
        persist("Review: Standard + Assigned \u2192 Claimed", "jexl",
                "labels.contains('review/standard') && status == 'ASSIGNED'",
                List.of(FilterAction.applyLabel("review/standard/claimed")));
        persist("Review: Standard + In-Progress \u2192 Active", "jexl",
                "labels.contains('review/standard') && status == 'IN_PROGRESS'",
                List.of(FilterAction.applyLabel("review/standard/active")));
        persist("Review: Routine + Pending \u2192 Unassigned", "jexl",
                "labels.contains('review/routine') && status == 'PENDING'",
                List.of(FilterAction.applyLabel("review/routine/unassigned")));
        persist("Review: Routine + Assigned \u2192 Claimed", "jexl",
                "labels.contains('review/routine') && status == 'ASSIGNED'",
                List.of(FilterAction.applyLabel("review/routine/claimed")));
        persist("Review: Routine + In-Progress \u2192 Active", "jexl",
                "labels.contains('review/routine') && status == 'IN_PROGRESS'",
                List.of(FilterAction.applyLabel("review/routine/active")));
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
