package io.quarkiverse.work.examples.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.quarkiverse.work.api.ChildSpec;
import io.quarkiverse.work.api.SpawnRequest;
import io.quarkiverse.work.api.SpawnResult;
import io.quarkiverse.work.examples.StepLog;
import io.quarkiverse.work.runtime.model.WorkItem;
import io.quarkiverse.work.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.work.runtime.model.WorkItemTemplate;
import io.quarkiverse.work.runtime.service.WorkItemService;
import io.quarkiverse.work.runtime.service.WorkItemSpawnService;

/**
 * Spawn scenario — demonstrates subprocess spawning.
 *
 * <p>
 * A loan-application WorkItem spawns three parallel child WorkItems
 * (credit-check, fraud-check, compliance-check), each carrying a distinct
 * {@code callerRef} that a CaseHub orchestrator would use to route child
 * completion back to the right {@code PlanItem}.
 *
 * <p>
 * quarkus-work fires per-child {@code WorkItemLifecycleEvent}s and makes no
 * decisions about what their completion means — that is CaseHub's concern.
 *
 * <p>
 * Endpoint: {@code POST /examples/spawn/run}
 */
@Path("/examples/spawn")
@Produces(MediaType.APPLICATION_JSON)
public class SpawnScenario {

    @Inject
    WorkItemService workItemService;

    @Inject
    WorkItemSpawnService spawnService;

    @POST
    @Path("/run")
    @Transactional
    public Map<String, Object> run() {
        final List<StepLog> steps = new ArrayList<>();

        // Step 1: create the three child templates
        final WorkItemTemplate creditTmpl = template("credit-check", "credit-review");
        final WorkItemTemplate fraudTmpl = template("fraud-check", "fraud-review");
        final WorkItemTemplate compTmpl = template("compliance-check", "compliance-review");
        steps.add(new StepLog(1, "Creates three check templates (credit, fraud, compliance)", null));

        // Step 2: create the parent WorkItem — in a real CaseHub deployment CaseHub does this
        final WorkItem parent = workItemService.create(new WorkItemCreateRequest(
                "Loan Application #" + UUID.randomUUID().toString().substring(0, 8),
                "Parallel approval checks for loan application",
                "loan-application",
                null,
                null,
                null,
                null,
                null,
                null,
                "finance-system",
                null,
                null,
                null,
                null,
                null,
                null,
                null, null, null));
        steps.add(new StepLog(2, "Creates loan-application WorkItem (parent)", parent.id));

        // Step 3: spawn three parallel child WorkItems
        // callerRef mimics what CaseHub embeds: caseId:planItemId for routing on completion
        final SpawnRequest spawnRequest = new SpawnRequest(
                parent.id,
                "loan-spawn-" + parent.id,
                List.of(
                        new ChildSpec(creditTmpl.id, "case:loan-" + parent.id + "/pi:credit", null),
                        new ChildSpec(fraudTmpl.id, "case:loan-" + parent.id + "/pi:fraud", null),
                        new ChildSpec(compTmpl.id, "case:loan-" + parent.id + "/pi:compliance", null)));

        final SpawnResult result = spawnService.spawn(spawnRequest);
        steps.add(new StepLog(3, "Spawns 3 parallel child WorkItems via POST /workitems/{id}/spawn", parent.id));
        steps.add(new StepLog(4,
                "Each child carries callerRef — CaseHub uses it to route completion back to the right PlanItem",
                null));

        final List<String> childIds = result.children().stream()
                .map(c -> c.workItemId().toString())
                .toList();

        return Map.of(
                "scenario", "parallel-spawn",
                "steps", steps,
                "parentWorkItemId", parent.id.toString(),
                "spawnGroupId", result.groupId().toString(),
                "childWorkItemIds", childIds,
                "note",
                "quarkus-work fires per-child events and makes no decisions about completion — that belongs to CaseHub");
    }

    private WorkItemTemplate template(final String name, final String category) {
        final WorkItemTemplate t = new WorkItemTemplate();
        t.name = name;
        t.category = category;
        t.createdBy = "spawn-scenario";
        t.persist();
        return t;
    }
}
