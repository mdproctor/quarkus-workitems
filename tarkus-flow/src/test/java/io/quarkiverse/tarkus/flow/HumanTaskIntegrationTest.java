package io.quarkiverse.tarkus.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.tarkus.runtime.model.WorkItem;
import io.quarkiverse.tarkus.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.tarkus.runtime.model.WorkItemPriority;
import io.quarkiverse.tarkus.runtime.model.WorkItemStatus;
import io.quarkiverse.tarkus.runtime.service.WorkItemService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestTransaction
class HumanTaskIntegrationTest {

    @Inject
    HumanTaskFlowBridge bridge;

    @Inject
    PendingWorkItemRegistry registry;

    @Inject
    WorkItemService service;

    @Test
    void requestApproval_createsWorkItemAndReturnsPendingFuture() {
        CompletableFuture<String> future = bridge.requestApproval(
                "Review document", "Please review and approve", "alice",
                WorkItemPriority.HIGH, "{\"docId\":\"123\"}");

        assertThat(future).isNotNull();
        assertThat(future.isDone()).isFalse();
    }

    @Test
    void requestApproval_workItemIsCreatedAndPending() {
        CompletableFuture<String> future = bridge.requestApproval(
                "Sign off report", null, "bob", WorkItemPriority.NORMAL, null);

        assertThat(registry.pendingCount()).isGreaterThanOrEqualTo(1);
        assertThat(future.isDone()).isFalse();
    }

    @Test
    void completeWorkItem_futureResolvesWithResolution() throws Exception {
        CompletableFuture<String> future = bridge.requestApproval(
                "Approve budget", null, "alice", WorkItemPriority.HIGH, null);

        List<WorkItem> all = WorkItem.listAll();
        WorkItem workItem = all.stream()
                .filter(wi -> "Approve budget".equals(wi.title))
                .findFirst()
                .orElseThrow();

        // Claim → start → complete (CDI event fires, listener completes future)
        service.claim(workItem.id, "alice");
        service.start(workItem.id, "alice");
        service.complete(workItem.id, "alice", "{\"approved\":true}");

        assertThat(future.isDone()).isTrue();
        assertThat(future.get()).isEqualTo("{\"approved\":true}");
    }

    @Test
    void rejectWorkItem_futureCompletesExceptionally() {
        CompletableFuture<String> future = bridge.requestApproval(
                "Budget rejection test", null, "carol", WorkItemPriority.LOW, null);

        List<WorkItem> all = WorkItem.listAll();
        WorkItem workItem = all.stream()
                .filter(wi -> "Budget rejection test".equals(wi.title))
                .findFirst()
                .orElseThrow();

        service.claim(workItem.id, "carol");
        service.reject(workItem.id, "carol", "out of budget");

        assertThat(future.isCompletedExceptionally()).isTrue();
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(WorkItemResolutionException.class)
                .hasMessageContaining("out of budget");
    }

    @Test
    void cancelWorkItem_futureCompletesExceptionally() {
        CompletableFuture<String> future = bridge.requestApproval(
                "Cancel test", null, "dave", WorkItemPriority.NORMAL, null);

        List<WorkItem> all = WorkItem.listAll();
        WorkItem workItem = all.stream()
                .filter(wi -> "Cancel test".equals(wi.title))
                .findFirst()
                .orElseThrow();

        service.cancel(workItem.id, "admin", "project cancelled");

        assertThat(future.isCompletedExceptionally()).isTrue();
    }

    @Test
    void requestGroupApproval_createsWorkItemWithCandidateGroups() {
        CompletableFuture<String> future = bridge.requestGroupApproval(
                "Team approval", "Needs team sign-off", "finance-team,leads",
                WorkItemPriority.HIGH, null);

        assertThat(future.isDone()).isFalse();
        List<WorkItem> all = WorkItem.listAll();
        WorkItem workItem = all.stream()
                .filter(wi -> "Team approval".equals(wi.title))
                .findFirst()
                .orElseThrow();
        assertThat(workItem.candidateGroups).isEqualTo("finance-team,leads");
        assertThat(workItem.status).isEqualTo(WorkItemStatus.PENDING);
    }

    @Test
    void unrelatedWorkItemCompletion_doesNotAffectPendingFuture() throws Exception {
        CompletableFuture<String> future = bridge.requestApproval(
                "Unrelated test", null, "eve", WorkItemPriority.NORMAL, null);

        // Create and complete a DIFFERENT WorkItem (not registered in registry)
        WorkItemCreateRequest req = new WorkItemCreateRequest(
                "Other item", null, null, null, WorkItemPriority.NORMAL,
                "frank", null, null, null, "test", null, null, null, null);
        WorkItem other = service.create(req);
        service.claim(other.id, "frank");
        service.start(other.id, "frank");
        service.complete(other.id, "frank", "{\"other\":true}");

        // The bridge's future should still be pending
        assertThat(future.isDone()).isFalse();
    }
}
