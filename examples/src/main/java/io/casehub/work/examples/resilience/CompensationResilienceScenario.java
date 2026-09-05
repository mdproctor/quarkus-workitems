package io.casehub.work.examples.resilience;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.casehub.work.api.WorkItem;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.WorkItemPriority;
import io.casehub.work.examples.StepLog;
import io.casehub.work.runtime.service.WorkItemService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

/**
 * Scenario 20 — Compensation Resilience.
 *
 * <p>
 * Demonstrates all three compensation guards and the full lifecycle of a
 * compensating WorkItem (including suspend and resume). Uses a hospital
 * patient referral system as the domain.
 *
 * <p>
 * Sub-scenarios:
 * <ol>
 * <li>Guard: cannot compensate a non-COMPLETED WorkItem</li>
 * <li>Guard: cannot double-compensate (happy path then retry)</li>
 * <li>Guard: cannot compensate a compensating WorkItem</li>
 * <li>Lifecycle: suspend → resume → complete on a compensating WorkItem</li>
 * </ol>
 *
 * <p>
 * Endpoint: {@code POST /examples/compensation-resilience/run}
 */
@Path("/examples/compensation-resilience")
@Produces(MediaType.APPLICATION_JSON)
public class CompensationResilienceScenario {

    private static final Logger LOG = Logger.getLogger(CompensationResilienceScenario.class);
    private static final String SCENARIO_ID = "compensation-resilience";

    @Inject
    WorkItemService workItemService;

    @POST
    @Path("/run")
    public CompensationResilienceResponse run() {
        final List<StepLog> steps = new ArrayList<>();

        // --- Sub-scenario 1: Guard — cannot compensate non-COMPLETED ---
        LOG.info("[SCENARIO] Sub-scenario 1: Guard — cannot compensate non-COMPLETED WorkItem");
        final UUID guard1ReferralId = QuarkusTransaction.requiringNew().call(() -> {
            final WorkItem referral = workItemService.create(WorkItemCreateRequest.builder()
                    .title("Patient referral: Smith → Cardiology")
                    .types(List.of("referral"))
                    .priority(WorkItemPriority.MEDIUM)
                    .candidateGroups("clinic-reception")
                    .createdBy("gp-reception")
                    .build());
            steps.add(new StepLog(1, "gp-reception creates referral (status: CREATED)", referral.id()));
            return referral.id();
        });

        final GuardResult nonCompletedGuard = tryCompensate(guard1ReferralId,
                "Cannot compensate non-COMPLETED WorkItem", steps, 2);

        // --- Sub-scenario 2: Happy path + Guard — cannot double-compensate ---
        final UUID completedReferralId = QuarkusTransaction.requiringNew().call(() -> {
            final WorkItem referral = workItemService.create(WorkItemCreateRequest.builder()
                    .title("Patient referral: Jones → Orthopaedics")
                    .types(List.of("referral"))
                    .priority(WorkItemPriority.MEDIUM)
                    .candidateGroups("clinic-reception")
                    .createdBy("gp-reception")
                    .build());
            workItemService.claim(referral.id(), "clinic-admin");
            workItemService.start(referral.id(), "clinic-admin");
            workItemService.complete(referral.id(), "clinic-admin", "Referral accepted", "ACCEPTED");
            steps.add(new StepLog(3, "gp-reception creates and completes referral", referral.id()));
            return referral.id();
        });

        final UUID compensatingWiId = QuarkusTransaction.requiringNew().call(() -> {
            final WorkItem comp = workItemService.compensate(completedReferralId,
                    WorkItemCreateRequest.builder()
                            .title("Cancel referral: Jones — specialist unavailable")
                            .types(List.of("referral", "compensation"))
                            .priority(WorkItemPriority.HIGH)
                            .candidateGroups("clinic-admin")
                            .createdBy("clinic-admin")
                            .build(),
                    "clinic-admin", "Specialist unavailable — referral must be redirected");
            steps.add(new StepLog(4, "clinic-admin compensates the referral", comp.id()));

            workItemService.claim(comp.id(), "clinic-admin");
            workItemService.start(comp.id(), "clinic-admin");
            workItemService.complete(comp.id(), "clinic-admin", "Referral cancelled and patient notified", "CANCELLED");
            steps.add(new StepLog(5, "clinic-admin completes compensation", comp.id()));
            return comp.id();
        });

        final GuardResult doubleCompensationGuard = tryCompensate(completedReferralId,
                "Cannot double-compensate", steps, 6);

        // --- Sub-scenario 3: Guard — cannot compensate a compensating WorkItem ---
        final GuardResult compensatorGuard = tryCompensate(compensatingWiId,
                "Cannot compensate a compensating WorkItem", steps, 7);

        // --- Sub-scenario 4: Lifecycle — suspend → resume → complete ---
        final UUID lifecycleOriginalId = QuarkusTransaction.requiringNew().call(() -> {
            final WorkItem referral = workItemService.create(WorkItemCreateRequest.builder()
                    .title("Patient referral: Brown → Neurology")
                    .types(List.of("referral"))
                    .priority(WorkItemPriority.MEDIUM)
                    .candidateGroups("clinic-reception")
                    .createdBy("gp-reception")
                    .build());
            workItemService.claim(referral.id(), "clinic-admin");
            workItemService.start(referral.id(), "clinic-admin");
            workItemService.complete(referral.id(), "clinic-admin", "Referral sent", "SENT");
            steps.add(new StepLog(8, "create and complete another referral", referral.id()));
            return referral.id();
        });

        final UUID lifecycleCompId = QuarkusTransaction.requiringNew().call(() -> {
            final WorkItem comp = workItemService.compensate(lifecycleOriginalId,
                    WorkItemCreateRequest.builder()
                            .title("Recall referral: Brown — incorrect specialist")
                            .types(List.of("referral", "compensation"))
                            .priority(WorkItemPriority.HIGH)
                            .candidateGroups("clinic-admin")
                            .createdBy("clinic-admin")
                            .build(),
                    "clinic-admin", "Wrong specialist — should be Neurosurgery not Neurology");
            steps.add(new StepLog(9, "compensate the referral", comp.id()));
            return comp.id();
        });

        final List<String> statusTrail = new ArrayList<>();

        QuarkusTransaction.requiringNew().run(() -> {
            workItemService.claim(lifecycleCompId, "clinic-admin");
            workItemService.start(lifecycleCompId, "clinic-admin");
            statusTrail.add(workItemService.findById(lifecycleCompId).orElseThrow().status().name());
        });

        QuarkusTransaction.requiringNew().run(() -> {
            workItemService.suspend(lifecycleCompId, "clinic-admin", "Doctor on leave — will resume Monday");
            statusTrail.add(workItemService.findById(lifecycleCompId).orElseThrow().status().name());
            steps.add(new StepLog(10, "compensating WorkItem suspended — doctor on leave", lifecycleCompId));
        });

        QuarkusTransaction.requiringNew().run(() -> {
            final WorkItem orig = workItemService.findById(lifecycleOriginalId).orElseThrow();
            steps.add(new StepLog(11, "original is still " + orig.compensationStatus() + " while compensating WI is suspended", lifecycleOriginalId));
        });

        QuarkusTransaction.requiringNew().run(() -> {
            workItemService.resume(lifecycleCompId, "senior-doctor");
            statusTrail.add(workItemService.findById(lifecycleCompId).orElseThrow().status().name());
            steps.add(new StepLog(12, "senior-doctor resumes the compensating WorkItem", lifecycleCompId));
        });

        QuarkusTransaction.requiringNew().run(() -> {
            workItemService.complete(lifecycleCompId, "senior-doctor",
                    "Referral recalled — patient redirected to Neurosurgery", "RECALLED");
            statusTrail.add(workItemService.findById(lifecycleCompId).orElseThrow().status().name());
            steps.add(new StepLog(13, "senior-doctor completes — original auto-COMPENSATED", lifecycleCompId));
        });

        final LifecycleResult lifecycleResult = QuarkusTransaction.requiringNew().call(() -> {
            final WorkItem finalOrig = workItemService.findById(lifecycleOriginalId).orElseThrow();
            return new LifecycleResult(lifecycleOriginalId, lifecycleCompId, statusTrail, finalOrig.compensationStatus().name());
        });

        return new CompensationResilienceResponse(
                SCENARIO_ID,
                steps,
                nonCompletedGuard,
                doubleCompensationGuard,
                compensatorGuard,
                lifecycleResult);
    }

    private GuardResult tryCompensate(UUID targetId, String description, List<StepLog> steps, int stepNum) {
        try {
            QuarkusTransaction.requiringNew().run(() ->
                    workItemService.compensate(targetId,
                            WorkItemCreateRequest.builder()
                                    .title("Guard test compensation")
                                    .types(List.of("compensation"))
                                    .candidateGroups("clinic-admin")
                                    .createdBy("clinic-admin")
                                    .build(),
                            "clinic-admin", "guard test"));
            return new GuardResult(description, false, "Guard did not fire");
        } catch (Exception e) {
            final String message = extractMessage(e);
            steps.add(new StepLog(stepNum, "Guard triggered: " + message, targetId));
            return new GuardResult(description, true, message);
        }
    }

    private static String extractMessage(Throwable t) {
        while (t.getCause() != null && !(t instanceof IllegalStateException)) {
            t = t.getCause();
        }
        return t.getMessage();
    }
}
