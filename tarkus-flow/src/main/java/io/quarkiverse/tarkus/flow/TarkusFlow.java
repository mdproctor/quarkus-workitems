package io.quarkiverse.tarkus.flow;

import java.util.function.Function;

import jakarta.inject.Inject;

import io.quarkiverse.flow.Flow;
import io.serverlessworkflow.fluent.func.dsl.FuncCallStep;
import io.serverlessworkflow.fluent.func.dsl.FuncDSL;

/**
 * Base class for Quarkus-Flow workflow definitions that include Tarkus WorkItem steps.
 *
 * <p>
 * Extend this instead of {@link Flow} to gain access to the {@link #workItem(String)}
 * DSL method, which creates WorkItem suspension steps that integrate naturally with
 * {@code function()}, {@code agent()}, and other quarkus-flow task types.
 *
 * <pre>
 * {
 *     &#64;code
 *     &#64;ApplicationScoped
 *     public class DocumentApprovalWorkflow extends TarkusFlow {
 *
 *         @Override
 *         public Workflow descriptor() {
 *             return workflow("document-approval")
 *                     .tasks(
 *                             workItem("legalReview")
 *                                     .title("Legal review required")
 *                                     .candidateGroups("legal-team")
 *                                     .priority(WorkItemPriority.HIGH)
 *                                     .payloadFrom((DocumentDraft d) -> d.toJson())
 *                                     .buildTask(DocumentDraft.class))
 *                     .build();
 *         }
 *     }
 * }
 * </pre>
 */
public abstract class TarkusFlow extends Flow {

    @Inject
    HumanTaskFlowBridge tarkusBridge;

    /**
     * Creates a Tarkus WorkItem suspension task for use inside {@code .tasks()}.
     *
     * @param name unique task name within the workflow definition
     * @return a builder for configuring the WorkItem parameters
     */
    protected TarkusTaskBuilder workItem(final String name) {
        return new TarkusTaskBuilder(name, tarkusBridge);
    }

    /**
     * Creates an automated function task for use inside {@code .tasks()}.
     *
     * <p>
     * Shorthand for {@link FuncDSL#function(String, Function, Class)} that mirrors
     * {@link #workItem(String)} so automated and human steps read at the same visual level:
     *
     * <pre>{@code
     * return workflow("my-workflow")
     *         .tasks(
     *                 fn("validate", (MyInput i) -> process(i), MyInput.class),
     *                 workItem("humanReview").title("Review result").buildTask(MyResult.class),
     *                 fn("finalise", (String resolution) -> archive(resolution), String.class))
     *         .build();
     * }</pre>
     *
     * @param name unique task name within the workflow definition
     * @param fn the function to execute; receives the previous step's output as input
     * @param inputClass the Java type of the input this task receives
     * @param <T> input type
     * @param <R> output type
     * @return a configured task ready for use in {@code .tasks(...)}
     */
    protected <T, R> FuncCallStep<T, R> fn(final String name, final Function<T, R> fn,
            final Class<T> inputClass) {
        return FuncDSL.function(name, fn, inputClass);
    }
}
