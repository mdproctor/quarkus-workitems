package io.quarkiverse.tarkus.flow;

import static io.serverlessworkflow.fluent.func.FuncWorkflowBuilder.workflow;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.tarkus.runtime.model.WorkItemPriority;
import io.serverlessworkflow.api.types.Workflow;

/**
 * Test workflow that exercises the TarkusFlow DSL.
 * Used by HumanTaskIntegrationTest to verify WorkItem creation via workItem() DSL.
 */
@ApplicationScoped
public class TestTarkusWorkflow extends TarkusFlow {

    @Override
    public Workflow descriptor() {
        return workflow("test-tarkus-flow")
                .tasks(
                        workItem("legalReview")
                                .title("Legal review required")
                                .candidateGroups("legal-team")
                                .priority(WorkItemPriority.HIGH)
                                .buildTask(Map.class))
                .build();
    }
}
