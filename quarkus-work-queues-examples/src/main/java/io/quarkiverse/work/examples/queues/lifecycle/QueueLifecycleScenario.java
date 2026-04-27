package io.quarkiverse.work.examples.queues.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.quarkiverse.work.queues.event.QueueEventType;
import io.quarkiverse.work.queues.model.FilterScope;
import io.quarkiverse.work.queues.model.QueueView;
import io.quarkiverse.work.runtime.model.WorkItem;
import io.quarkiverse.work.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.work.runtime.model.WorkItemPriority;
import io.quarkiverse.work.runtime.service.WorkItemService;

/**
 * Runnable example demonstrating the full queue lifecycle event sequence:
 * {@code ADDED → CHANGED → CHANGED → REMOVED}.
 *
 * <p>
 * This example is specifically designed to make the {@code QueueMembershipTracker}
 * design constraints visible. Each step's explanation shows both what the tracker
 * produces (correct) and what a naïve live-entity snapshot would produce (wrong).
 *
 * <h2>Scenario steps</h2>
 * <ol>
 * <li><strong>Create + label</strong>: WorkItem created (no label yet → not in queue).
 * Then {@code addLabel("lifecycle-demo/case-1")} → filter engine runs →
 * {@code ADDED} fires.</li>
 * <li><strong>Claim</strong>: filter re-evaluates; MANUAL label survives the INFERRED
 * strip → {@code CHANGED} fires.</li>
 * <li><strong>Start</strong>: same re-evaluation; still in queue → {@code CHANGED} fires.</li>
 * <li><strong>Remove label</strong>: {@code removeLabel()} persists the removal, then the
 * filter engine runs with no matching labels → {@code REMOVED} fires.</li>
 * </ol>
 *
 * <h2>Why ADDED fires in step 1 (tracker invariant)</h2>
 * <p>
 * {@code WorkItemLifecycleEvent} fires AFTER the WorkItem mutation is persisted. When
 * the observer fetches the entity, the label is already in the store. A naïve live
 * snapshot would see:
 *
 * <pre>
 *   before = {Q}   ← label already present in store when observer fetches entity
 *   after  = {Q}   ← label survives evaluate()
 *   result: CHANGED  ← WRONG — item was just entering the queue for the first time
 * </pre>
 *
 * The tracker correctly returns empty for a new item:
 *
 * <pre>
 *   before = {}    ← no prior history in work_item_queue_membership table
 *   after  = {Q}
 *   result: ADDED  ← correct
 * </pre>
 *
 * <h2>Why REMOVED fires in step 4 (tracker invariant)</h2>
 * <p>
 * Label removal is also persisted before the event fires. A naïve live snapshot sees:
 *
 * <pre>
 *   before = {}    ← label already gone when observer fetches entity
 *   after  = {}    ← no labels → not in queue
 *   result: no event  ← WRONG — item just left a queue it was in
 * </pre>
 *
 * The tracker remembers the post-step-3 membership:
 *
 * <pre>
 *   before = {Q}   ← loaded from work_item_queue_membership table
 *   after  = {}    ← label removed
 *   result: REMOVED  ← correct
 * </pre>
 *
 * <h2>Why CHANGED not REMOVED+ADDED in steps 2–3 (context invariant)</h2>
 * <p>
 * The filter engine strips all INFERRED labels before re-applying them. Without the
 * {@code QueueMembershipContext} before/after boundary, this would emit REMOVED (strip)
 * then ADDED (re-apply) within one operation. The context collapses these into CHANGED
 * by comparing membership only at operation boundaries, not during the strip.
 *
 * <h2>Persistence across restarts</h2>
 * <p>
 * The {@code work_item_queue_membership} table (V2001 migration) stores the tracker state.
 * On JVM restart the tracker reads from DB — items currently in queues produce CHANGED
 * (not a spurious re-ADDED) on their next lifecycle event.
 *
 * <h2>Running</h2>
 *
 * <pre>
 *   curl -s -X POST http://localhost:8080/queue-examples/lifecycle/run | jq .
 * </pre>
 */
@Path("/queue-examples/lifecycle")
@Produces(MediaType.APPLICATION_JSON)
public class QueueLifecycleScenario {

    @Inject
    WorkItemService workItemService;

    @Inject
    QueueEventLog eventLog;

    /**
     * Run the lifecycle scenario and return a step-by-step trace of queue events.
     *
     * <p>
     * Each step record names the operation, which queue was affected, and which event type
     * fired. The response makes it easy to verify that ADDED/CHANGED/REMOVED fire in the
     * correct order with no spurious duplicates.
     *
     * @return scenario result containing all captured queue events grouped by step
     */
    @POST
    @Path("/run")
    public QueueLifecycleResponse run() {
        eventLog.clear();
        final List<QueueLifecycleResponse.Step> steps = new ArrayList<>();
        final String queuePattern = "lifecycle-demo/**";

        // ── Setup ─────────────────────────────────────────────────────────────
        // Create the queue view that the scenario exercises.
        // In a real application this would be configured once at startup.
        final QueueView queue = createQueueView("Lifecycle Demo Queue", queuePattern);
        final UUID queueId = queue.id;

        // ── Step 1: ADDED ─────────────────────────────────────────────────────
        // Creating the WorkItem with a MANUAL label matching the queue triggers
        // the filter engine. The before-state has no queue membership (new item),
        // the after-state has the queue → ADDED fires.
        final WorkItemCreateRequest createReq = new WorkItemCreateRequest(
                "Queue lifecycle demo item", null, "demo", null,
                WorkItemPriority.HIGH, null, null, null, null,
                "demo", null, null, null, null, null, null, null, null, null);
        final WorkItem wi = workItemService.create(createReq);
        final UUID itemId = wi.id;

        // Add the MANUAL label that makes the item a member of the queue.
        // TRACKER INVARIANT: WorkItemLifecycleEvent fires AFTER persistence.
        // The label is already in the store when the observer runs. A live snapshot
        // would see before={Q}, after={Q} → CHANGED (wrong). The tracker has no
        // entry for this new item → before={} → ADDED fires correctly.
        workItemService.addLabel(itemId, "lifecycle-demo/case-1", "demo");
        steps.add(new QueueLifecycleResponse.Step(
                1,
                "WorkItem created and labelled 'lifecycle-demo/case-1'",
                "Tracker before={} (new item). After={Q}. → ADDED. "
                        + "Without tracker: live snapshot would see before={Q} → CHANGED (wrong).",
                captureEvents(queueId, itemId)));

        // ── Step 2: CHANGED ───────────────────────────────────────────────────
        // Claiming fires a lifecycle event. The filter engine re-evaluates: INFERRED
        // labels are stripped (none here) and re-applied. The MANUAL label survives.
        // Tracker before={Q} (recorded at end of step 1), after={Q} → CHANGED.
        workItemService.claim(itemId, "alice");
        steps.add(new QueueLifecycleResponse.Step(
                2,
                "WorkItem claimed by 'alice'",
                "Tracker before={Q}. Filter re-evaluates (INFERRED strip+re-apply). MANUAL label survives. After={Q}. → CHANGED.",
                captureEvents(queueId, itemId)));

        // ── Step 3: CHANGED again ─────────────────────────────────────────────
        workItemService.start(itemId, "alice");
        steps.add(new QueueLifecycleResponse.Step(
                3,
                "WorkItem started by 'alice'",
                "Tracker before={Q} (from step 2). Filter re-evaluates. MANUAL label still present. After={Q}. → CHANGED.",
                captureEvents(queueId, itemId)));

        // ── Step 4: REMOVED ───────────────────────────────────────────────────
        // removeLabel() persists the deletion, then fires LABEL_REMOVED → filter runs.
        // TRACKER INVARIANT: label is already gone from the store when the observer runs.
        // A live snapshot would see before={}, after={} → no event (wrong).
        // The tracker holds the step-3 membership → before={Q}, after={} → REMOVED fires.
        workItemService.removeLabel(itemId, "lifecycle-demo/case-1");
        steps.add(new QueueLifecycleResponse.Step(
                4,
                "MANUAL label 'lifecycle-demo/case-1' removed",
                "Tracker before={Q} (from step 3). Label already deleted in store. After={}. → REMOVED. "
                        + "Without tracker: live snapshot would see before={} → no event (wrong).",
                captureEvents(queueId, itemId)));

        return new QueueLifecycleResponse("queue-lifecycle-demo", steps);
    }

    @Transactional
    QueueView createQueueView(final String name, final String pattern) {
        final QueueView queue = new QueueView();
        queue.name = name;
        queue.labelPattern = pattern;
        queue.scope = FilterScope.ORG;
        queue.persist();
        return queue;
    }

    private List<QueueEventType> captureEvents(final UUID queueId, final UUID itemId) {
        return eventLog.drain().stream()
                .filter(e -> e.queueViewId().equals(queueId) && e.workItemId().equals(itemId))
                .map(QueueEventLog.Entry::eventType)
                .toList();
    }
}
