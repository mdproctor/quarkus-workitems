package io.quarkiverse.tarkus.flow;

import java.util.function.Function;

import io.quarkiverse.tarkus.runtime.model.WorkItemPriority;
import io.serverlessworkflow.fluent.func.configurers.FuncTaskConfigurer;
import io.serverlessworkflow.fluent.func.dsl.FuncDSL;
import io.smallrye.mutiny.Uni;

/**
 * Fluent builder for creating Tarkus WorkItem suspension tasks
 * within Quarkus-Flow workflow definitions.
 *
 * <p>
 * Obtained via {@link TarkusFlow#workItem(String)}.
 *
 * <pre>{@code
 * return workflow("document-approval")
 *         .tasks(
 *                 workItem("legalReview")
 *                         .title("Legal review required")
 *                         .candidateGroups("legal-team")
 *                         .priority(WorkItemPriority.HIGH)
 *                         .payloadFrom((DocumentDraft d) -> d.toJson())
 *                         .buildTask(DocumentDraft.class))
 *         .build();
 * }</pre>
 */
public class TarkusTaskBuilder {

    private final String name;
    private final HumanTaskFlowBridge bridge;
    private String title;
    private String description;
    private String assigneeId;
    private String candidateGroups;
    private WorkItemPriority priority = WorkItemPriority.NORMAL;
    private Function<Object, String> payloadExtractor;

    TarkusTaskBuilder(final String name, final HumanTaskFlowBridge bridge) {
        this.name = name;
        this.bridge = bridge;
    }

    /**
     * Human-readable task name shown in the Tarkus inbox (required).
     *
     * @param title the task title
     * @return this builder
     */
    public TarkusTaskBuilder title(final String title) {
        this.title = title;
        return this;
    }

    /**
     * Longer description of what the human needs to do (optional).
     *
     * @param description the task description
     * @return this builder
     */
    public TarkusTaskBuilder description(final String description) {
        this.description = description;
        return this;
    }

    /**
     * Assign the WorkItem directly to a specific user (optional).
     * Use {@link #candidateGroups(String)} instead for work-queue routing.
     *
     * @param assigneeId the user ID to assign to
     * @return this builder
     */
    public TarkusTaskBuilder assigneeId(final String assigneeId) {
        this.assigneeId = assigneeId;
        return this;
    }

    /**
     * Route the WorkItem to a work queue for any member of these groups to claim.
     * Comma-separated (e.g. {@code "finance-team,managers"}).
     *
     * @param candidateGroups comma-separated group names
     * @return this builder
     */
    public TarkusTaskBuilder candidateGroups(final String candidateGroups) {
        this.candidateGroups = candidateGroups;
        return this;
    }

    /**
     * Task priority. Defaults to {@link WorkItemPriority#NORMAL}.
     *
     * @param priority the priority level
     * @return this builder
     */
    public TarkusTaskBuilder priority(final WorkItemPriority priority) {
        this.priority = priority;
        return this;
    }

    /**
     * Extract a JSON payload from the workflow step's input at execution time.
     * The extracted JSON is stored on the WorkItem so the human has context.
     *
     * @param extractor a function from the input type to a JSON string
     * @param <T> the input type
     * @return this builder
     */
    @SuppressWarnings("unchecked")
    public <T> TarkusTaskBuilder payloadFrom(final Function<T, String> extractor) {
        this.payloadExtractor = (Function<Object, String>) extractor;
        return this;
    }

    // Accessors for tests

    /** Returns the configured task title. */
    public String getTitle() {
        return title;
    }

    /** Returns the configured assignee ID, or null if not set. */
    public String getAssigneeId() {
        return assigneeId;
    }

    /** Returns the configured candidate groups, or null if not set. */
    public String getCandidateGroups() {
        return candidateGroups;
    }

    /** Returns the configured priority (never null; defaults to NORMAL). */
    public WorkItemPriority getPriority() {
        return priority;
    }

    /** Returns the payload extractor function, or null if not configured. */
    public Function<Object, String> getPayloadExtractor() {
        return payloadExtractor;
    }

    /**
     * Builds a Quarkus-Flow compatible task that creates a Tarkus WorkItem
     * and suspends the workflow until a human or agent resolves it.
     *
     * <p>
     * The returned {@link FuncTaskConfigurer} can be passed directly to
     * {@code FuncWorkflowBuilder.tasks(...)}.
     *
     * @param inputClass the Java type of the input this task receives from the previous step
     * @param <T> the input type
     * @return a configured task ready for use in a workflow descriptor
     * @throws IllegalStateException if {@link #title(String)} was not called
     */
    public <T> FuncTaskConfigurer buildTask(final Class<T> inputClass) {
        if (title == null || title.isBlank()) {
            throw new IllegalStateException("TarkusTaskBuilder requires a title");
        }
        final String taskName = name;
        final String taskTitle = title;
        final String taskDescription = description;
        final String taskAssigneeId = assigneeId;
        final String taskCandidateGroups = candidateGroups;
        final WorkItemPriority taskPriority = priority;
        final Function<Object, String> taskPayloadExtractor = payloadExtractor;
        final HumanTaskFlowBridge taskBridge = bridge;

        return FuncDSL.function(
                taskName,
                (T input) -> {
                    final String payload = taskPayloadExtractor != null
                            ? taskPayloadExtractor.apply(input)
                            : null;
                    final Uni<String> result;
                    if (taskCandidateGroups != null) {
                        result = taskBridge.requestGroupApproval(
                                taskTitle, taskDescription, taskCandidateGroups,
                                taskPriority, payload);
                    } else {
                        result = taskBridge.requestApproval(
                                taskTitle, taskDescription, taskAssigneeId,
                                taskPriority, payload);
                    }
                    return result;
                },
                inputClass);
    }
}
