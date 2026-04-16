package io.quarkiverse.workitems.queues.service;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.workitems.queues.model.FilterAction;
import io.quarkiverse.workitems.queues.model.FilterChain;
import io.quarkiverse.workitems.queues.model.WorkItemFilter;
import io.quarkiverse.workitems.runtime.model.LabelPersistence;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemLabel;
import io.quarkiverse.workitems.runtime.repository.WorkItemRepository;

/**
 * Full implementation of the filter evaluation engine.
 *
 * <p>
 * On every WorkItem mutation, strips all INFERRED labels and re-applies them via a
 * multi-pass loop (max {@value #MAX_PASSES} passes). Each pass iterates all active
 * saved filters (JEXL/JQ) and CDI lambda filters. The loop continues while at least
 * one new label is applied, enabling filter chains where filter B reacts to a label
 * applied by filter A. Circular configurations (ALWAYS → same label) converge
 * immediately after the first pass because duplicates are skipped.
 *
 * <p>
 * The inverse index ({@link FilterChain}) is maintained as filters fire, enabling
 * O(affected) cascade when a filter is deleted rather than a full WorkItem scan.
 */
@ApplicationScoped
public class FilterEngineImpl implements FilterEngine {

    private static final int MAX_PASSES = 10;

    @Inject
    FilterEvaluatorRegistry registry;

    @Inject
    LambdaFilterRegistry lambdaRegistry;

    @Inject
    WorkItemRepository workItemRepo;

    /**
     * Evaluates all active filters against the given WorkItem.
     *
     * <p>
     * Algorithm:
     * <ol>
     * <li>Strip all INFERRED labels.</li>
     * <li>Multi-pass: for each active saved filter, evaluate condition; if matched, apply
     * APPLY_LABEL actions and record workItem in the filter's {@link FilterChain}.</li>
     * <li>Evaluate all CDI lambda filters each pass.</li>
     * <li>Repeat until no new labels were applied or {@value #MAX_PASSES} passes reached.</li>
     * <li>Persist the updated WorkItem.</li>
     * </ol>
     *
     * @param workItem the WorkItem to evaluate; mutated in place
     */
    @Override
    @Transactional
    public void evaluate(final WorkItem workItem) {
        // Strip all INFERRED labels before re-evaluation
        workItem.labels.removeIf(l -> l.persistence == LabelPersistence.INFERRED);

        // Multi-pass until stable (handles propagation chains; max passes cap terminates circular)
        boolean changed = true;
        int passes = 0;
        while (changed && passes < MAX_PASSES) {
            changed = false;
            passes++;

            // Saved filters (JEXL, JQ)
            for (final WorkItemFilter filter : WorkItemFilter.findActive()) {
                final FilterConditionEvaluator evaluator = registry.find(filter.conditionLanguage);
                if (evaluator != null && evaluator.evaluate(workItem, filter.conditionExpression)) {
                    changed |= applyActions(workItem, filter.parseActions(), filter.id.toString());
                    // Maintain inverse index: record this WorkItem in the filter's chain
                    final FilterChain chain = FilterChain.findOrCreateForFilter(filter.id);
                    chain.workItems.add(workItem.id);
                }
            }

            // Lambda filters (CDI beans) — no inverse index; they can't be deleted via REST
            for (final WorkItemFilterBean bean : lambdaRegistry.all()) {
                if (bean.matches(workItem)) {
                    changed |= applyActions(workItem, bean.actions(), bean.getClass().getSimpleName());
                }
            }
        }

        workItemRepo.save(workItem);
    }

    /**
     * Removes all INFERRED labels applied by the given filter from every WorkItem in
     * its {@link FilterChain}, then deletes the chain record.
     *
     * <p>
     * After removing the deleted filter's labels, each affected WorkItem is re-evaluated
     * against all remaining active filters (excluding the deleted one). This restores any
     * labels that are still justified by other matching filters — e.g. when two filters
     * both apply the same label, deleting one should not remove the label if the other
     * still matches.
     *
     * <p>
     * The caller (FilterResource) is responsible for deleting the {@link WorkItemFilter}
     * record itself; this method only cascades the label removal.
     *
     * @param filterId the ID of the filter being deleted
     */
    @Override
    @Transactional
    public void cascadeDelete(final UUID filterId) {
        final FilterChain chain = FilterChain.findByFilterId(filterId);
        if (chain == null) {
            return;
        }

        for (final UUID workItemId : chain.workItems) {
            workItemRepo.findById(workItemId).ifPresent(wi -> evaluateExcluding(wi, filterId));
        }

        chain.delete();
    }

    /**
     * Re-evaluates all active filters against the given WorkItem, skipping the filter
     * identified by {@code excludeFilterId}. Used during cascade delete to restore labels
     * that are still justified by other active filters.
     *
     * @param workItem the WorkItem to re-evaluate; mutated in place and persisted
     * @param excludeFilterId the filter to skip during re-evaluation
     */
    private void evaluateExcluding(final WorkItem workItem, final UUID excludeFilterId) {
        // Strip all INFERRED labels before re-evaluation
        workItem.labels.removeIf(l -> l.persistence == LabelPersistence.INFERRED);

        boolean changed = true;
        int passes = 0;
        while (changed && passes < MAX_PASSES) {
            changed = false;
            passes++;

            for (final WorkItemFilter filter : WorkItemFilter.findActive()) {
                if (filter.id.equals(excludeFilterId)) {
                    continue; // skip the filter being deleted
                }
                final FilterConditionEvaluator evaluator = registry.find(filter.conditionLanguage);
                if (evaluator != null && evaluator.evaluate(workItem, filter.conditionExpression)) {
                    changed |= applyActions(workItem, filter.parseActions(), filter.id.toString());
                    final FilterChain fc = FilterChain.findOrCreateForFilter(filter.id);
                    fc.workItems.add(workItem.id);
                }
            }

            for (final WorkItemFilterBean bean : lambdaRegistry.all()) {
                if (bean.matches(workItem)) {
                    changed |= applyActions(workItem, bean.actions(), bean.getClass().getSimpleName());
                }
            }
        }

        workItemRepo.save(workItem);
    }

    /**
     * Applies a list of filter actions to a WorkItem, returning {@code true} if any
     * new label was added.
     *
     * @param wi the WorkItem to mutate
     * @param actions the actions to apply
     * @param appliedBy the filterId or bean name to record on each applied label
     * @return true if at least one new label was added
     */
    private boolean applyActions(final WorkItem wi, final List<FilterAction> actions,
            final String appliedBy) {
        boolean applied = false;
        for (final FilterAction action : actions) {
            if ("APPLY_LABEL".equals(action.type()) && action.labelPath() != null) {
                final boolean exists = wi.labels.stream()
                        .anyMatch(l -> l.path.equals(action.labelPath()));
                if (!exists) {
                    wi.labels.add(new WorkItemLabel(action.labelPath(), LabelPersistence.INFERRED, appliedBy));
                    applied = true;
                }
            }
        }
        return applied;
    }
}
