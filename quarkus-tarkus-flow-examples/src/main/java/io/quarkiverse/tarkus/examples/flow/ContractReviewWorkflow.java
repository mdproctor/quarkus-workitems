package io.quarkiverse.tarkus.examples.flow;

import static io.serverlessworkflow.fluent.func.FuncWorkflowBuilder.workflow;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.tarkus.flow.TarkusFlow;
import io.quarkiverse.tarkus.runtime.model.WorkItemPriority;
import io.serverlessworkflow.api.types.Workflow;

/**
 * A multi-step contract review workflow demonstrating the TarkusFlow DSL.
 *
 * <p>
 * This workflow mixes automated {@code function()} steps with human {@code workItem()} steps,
 * showing how a business process can hand off between machines and people without
 * any explicit thread management or polling — Quarkus-Flow suspends the workflow
 * coroutine at each {@code workItem()} step and resumes it when a human acts.
 *
 * <h2>Workflow steps</h2>
 * <ol>
 * <li><b>validate</b> — automated: validates the contract submission (milliseconds)</li>
 * <li><b>legalReview</b> — human: any member of {@code legal-team} reviews via the inbox</li>
 * <li><b>executiveSignOff</b> — human: a named executive gives final sign-off</li>
 * <li><b>countersign</b> — automated: records the execution with both decisions</li>
 * </ol>
 *
 * <h2>DSL features demonstrated</h2>
 * <ul>
 * <li>{@link io.quarkiverse.tarkus.flow.TarkusTaskBuilder#title(String)} — inbox display name</li>
 * <li>{@link io.quarkiverse.tarkus.flow.TarkusTaskBuilder#description(String)} — reviewer context</li>
 * <li>{@link io.quarkiverse.tarkus.flow.TarkusTaskBuilder#candidateGroups(String)} — work-queue routing</li>
 * <li>{@link io.quarkiverse.tarkus.flow.TarkusTaskBuilder#assigneeId(String)} — direct assignment</li>
 * <li>{@link io.quarkiverse.tarkus.flow.TarkusTaskBuilder#priority(WorkItemPriority)} — HIGH vs CRITICAL</li>
 * <li>{@link io.quarkiverse.tarkus.flow.TarkusTaskBuilder#payloadFrom} — extract JSON context from input</li>
 * </ul>
 *
 * <h2>Starting the workflow</h2>
 *
 * <pre>{@code
 * contractReviewWorkflow.startInstance(Map.of(
 *         "contractId", "CTR-2024-001",
 *         "party", "Acme Corp",
 *         "value", "£125,000"));
 * }</pre>
 */
@ApplicationScoped
public class ContractReviewWorkflow extends TarkusFlow {

    @Override
    public Workflow descriptor() {
        return workflow("contract-review")
                .tasks(

                        // ── Step 1: Automated validation ─────────────────────────────────────
                        // Runs immediately — no human involved.
                        // Extracts the submission fields and stamps a validation status.
                        fn("validate",
                                (Map submission) -> Map.of(
                                        "contractId", submission.getOrDefault("contractId", "unknown"),
                                        "party", submission.getOrDefault("party", "unknown"),
                                        "value", submission.getOrDefault("value", "unknown"),
                                        "status", "validated"),
                                Map.class),

                        // ── Step 2: Legal team review ─────────────────────────────────────────
                        // Any member of the "legal-team" group can claim this from the inbox.
                        // payloadFrom() serialises the validated contract fields as JSON context
                        // so the reviewer sees the contract details without leaving the inbox.
                        // Suspends until a team member claims, starts, and completes the WorkItem.
                        workItem("legalReview")
                                .title("Legal review: contract approval")
                                .description(
                                        "Review contract terms for legal compliance. "
                                                + "Check liability clauses, IP ownership, and termination conditions. "
                                                + "Approve or reject with a brief rationale.")
                                .candidateGroups("legal-team")
                                .priority(WorkItemPriority.HIGH)
                                .payloadFrom((Map draft) -> String.format(
                                        "{\"contractId\":\"%s\",\"party\":\"%s\",\"value\":\"%s\",\"status\":\"%s\"}",
                                        draft.get("contractId"),
                                        draft.get("party"),
                                        draft.get("value"),
                                        draft.get("status")))
                                .buildTask(Map.class),

                        // ── Step 3: Executive sign-off ────────────────────────────────────────
                        // Directly assigned to a named executive — not a group queue.
                        // Receives the legal team's resolution JSON as its input.
                        // CRITICAL priority ensures it surfaces at the top of the inbox.
                        // Suspends until the executive claims and completes the WorkItem.
                        workItem("executiveSignOff")
                                .title("Executive sign-off required")
                                .description(
                                        "Legal team has approved. Final executive sign-off before contract execution. "
                                                + "Review the legal decision and confirm to proceed.")
                                .assigneeId("exec-officer")
                                .priority(WorkItemPriority.CRITICAL)
                                .buildTask(String.class),

                        // ── Step 4: Automated countersigning ─────────────────────────────────
                        // Runs immediately after executive approval.
                        // Combines the executive's decision with the contract ID for the audit record.
                        fn("countersign",
                                (String executiveDecision) -> "EXECUTED — " + executiveDecision,
                                String.class))

                .build();
    }
}
