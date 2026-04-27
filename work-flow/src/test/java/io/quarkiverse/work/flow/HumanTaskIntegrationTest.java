package io.quarkiverse.work.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.work.runtime.model.WorkItem;
import io.quarkiverse.work.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.work.runtime.model.WorkItemPriority;
import io.quarkiverse.work.runtime.model.WorkItemStatus;
import io.quarkiverse.work.runtime.service.WorkItemService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;

@QuarkusTest
@TestTransaction
class HumanTaskIntegrationTest {

    @Inject
    HumanTaskFlowBridge bridge;

    @Inject
    PendingWorkItemRegistry registry;

    @Inject
    WorkItemService service;

    @Inject
    TestWorkItemsWorkflow testWorkflow;

    @Test
    void requestApproval_createsWorkItemAndReturnsPendingUni() {
        Uni<String> result = bridge.requestApproval(
                "Review document", "Please review and approve", "alice",
                WorkItemPriority.HIGH, "{\"docId\":\"123\"}");

        assertThat(result).isNotNull();
        // Uni is pending — awaiting it should time out
        assertThatThrownBy(() -> result.await().atMost(Duration.ofMillis(50)))
                .isInstanceOf(Exception.class);
    }

    @Test
    void requestApproval_workItemIsCreatedAndPending() {
        Uni<String> result = bridge.requestApproval(
                "Sign off report", null, "bob", WorkItemPriority.NORMAL, null);

        assertThat(registry.pendingCount()).isGreaterThanOrEqualTo(1);
        assertThatThrownBy(() -> result.await().atMost(Duration.ofMillis(50)))
                .isInstanceOf(Exception.class);
    }

    @Test
    void completeWorkItem_uniResolvesWithResolution() {
        Uni<String> result = bridge.requestApproval(
                "Approve budget", null, "alice", WorkItemPriority.HIGH, null);

        List<WorkItem> all = WorkItem.listAll();
        WorkItem workItem = all.stream()
                .filter(wi -> "Approve budget".equals(wi.title))
                .findFirst()
                .orElseThrow();

        // Claim → start → complete (CDI event fires, listener completes the underlying future)
        service.claim(workItem.id, "alice");
        service.start(workItem.id, "alice");
        service.complete(workItem.id, "alice", "{\"approved\":true}");

        String resolution = result.await().atMost(Duration.ofSeconds(5));
        assertThat(resolution).isEqualTo("{\"approved\":true}");
    }

    @Test
    void rejectWorkItem_uniFailsWithException() {
        Uni<String> result = bridge.requestApproval(
                "Budget rejection test", null, "carol", WorkItemPriority.LOW, null);

        List<WorkItem> all = WorkItem.listAll();
        WorkItem workItem = all.stream()
                .filter(wi -> "Budget rejection test".equals(wi.title))
                .findFirst()
                .orElseThrow();

        service.claim(workItem.id, "carol");
        service.reject(workItem.id, "carol", "out of budget");

        assertThatThrownBy(() -> result.await().atMost(Duration.ofSeconds(5)))
                .isInstanceOf(WorkItemResolutionException.class)
                .hasMessageContaining("out of budget");
    }

    @Test
    void cancelWorkItem_uniFailsWithException() {
        Uni<String> result = bridge.requestApproval(
                "Cancel test", null, "dave", WorkItemPriority.NORMAL, null);

        List<WorkItem> all = WorkItem.listAll();
        WorkItem workItem = all.stream()
                .filter(wi -> "Cancel test".equals(wi.title))
                .findFirst()
                .orElseThrow();

        service.cancel(workItem.id, "admin", "project cancelled");

        assertThatThrownBy(() -> result.await().atMost(Duration.ofSeconds(5)))
                .isInstanceOf(WorkItemResolutionException.class);
    }

    @Test
    void requestGroupApproval_createsWorkItemWithCandidateGroups() {
        Uni<String> result = bridge.requestGroupApproval(
                "Team approval", "Needs team sign-off", "finance-team,leads",
                WorkItemPriority.HIGH, null);

        assertThatThrownBy(() -> result.await().atMost(Duration.ofMillis(50)))
                .isInstanceOf(Exception.class);
        List<WorkItem> all = WorkItem.listAll();
        WorkItem workItem = all.stream()
                .filter(wi -> "Team approval".equals(wi.title))
                .findFirst()
                .orElseThrow();
        assertThat(workItem.candidateGroups).isEqualTo("finance-team,leads");
        assertThat(workItem.status).isEqualTo(WorkItemStatus.PENDING);
    }

    @Test
    void unrelatedWorkItemCompletion_doesNotAffectPendingUni() {
        Uni<String> result = bridge.requestApproval(
                "Unrelated test", null, "eve", WorkItemPriority.NORMAL, null);

        // Create and complete a DIFFERENT WorkItem (not registered in registry)
        WorkItemCreateRequest req = new WorkItemCreateRequest(
                "Other item", null, null, null, WorkItemPriority.NORMAL,
                "frank", null, null, null, "test", null, null, null, null, null, null, null, null, null);
        WorkItem other = service.create(req);
        service.claim(other.id, "frank");
        service.start(other.id, "frank");
        service.complete(other.id, "frank", "{\"other\":true}");

        // The bridge's Uni should still be pending
        assertThatThrownBy(() -> result.await().atMost(Duration.ofMillis(50)))
                .isInstanceOf(Exception.class);
    }

    // -- Uni<String> API tests --

    @Test
    void requestApproval_returnsUniThatResolvesWithResolution() {
        Uni<String> result = bridge.requestApproval(
                "Uni test", null, "alice", WorkItemPriority.NORMAL, null);

        assertThat(result).isNotNull();

        List<WorkItem> all = WorkItem.listAll();
        WorkItem wi = all.stream()
                .filter(w -> "Uni test".equals(w.title))
                .findFirst().orElseThrow();

        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        service.complete(wi.id, "alice", "{\"approved\":true}");

        String resolution = result.await().atMost(Duration.ofSeconds(5));
        assertThat(resolution).isEqualTo("{\"approved\":true}");
    }

    @Test
    void requestApproval_rejectCausesUniFailure() {
        Uni<String> result = bridge.requestApproval(
                "Uni reject test", null, "bob", WorkItemPriority.NORMAL, null);

        List<WorkItem> all = WorkItem.listAll();
        WorkItem wi = all.stream()
                .filter(w -> "Uni reject test".equals(w.title))
                .findFirst().orElseThrow();

        service.claim(wi.id, "bob");
        service.reject(wi.id, "bob", "not applicable");

        assertThatThrownBy(() -> result.await().atMost(Duration.ofSeconds(5)))
                .isInstanceOf(WorkItemResolutionException.class);
    }

    @Test
    void requestGroupApproval_returnsUni() {
        Uni<String> result = bridge.requestGroupApproval(
                "Group uni test", null, "finance-team", WorkItemPriority.HIGH, null);
        assertThat(result).isNotNull();
        // Uni is pending — times out since no one resolves it
        assertThatThrownBy(() -> result.await().atMost(Duration.ofMillis(100)))
                .isInstanceOf(Exception.class);
    }

    // -- WorkItemsFlow DSL integration test --

    @Test
    void workItemsDslFlow_createsWorkItemAndSuspends() {
        List<WorkItem> before = WorkItem.listAll();

        // Start the workflow asynchronously — it suspends on the WorkItem creation
        testWorkflow.startInstance(Map.of("docTitle", "My Document"))
                .subscribe().with(__ -> {
                }, __ -> {
                });

        // Give it a moment to create the WorkItem
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        List<WorkItem> after = WorkItem.listAll();

        // A WorkItem should have been created by the workflow
        assertThat(after.size()).isGreaterThan(before.size());
        WorkItem wi = after.stream()
                .filter(w -> "legal-team".equals(w.candidateGroups))
                .findFirst().orElseThrow();
        assertThat(wi.status).isEqualTo(WorkItemStatus.PENDING);
    }
}
