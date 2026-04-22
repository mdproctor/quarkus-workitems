package io.quarkiverse.workitems.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.workitems.runtime.event.WorkItemLifecycleEvent;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemStatus;

class WorkItemFlowEventListenerTest {

    private PendingWorkItemRegistry registry;
    private WorkItemFlowEventListener listener;

    @BeforeEach
    void setUp() {
        registry = new PendingWorkItemRegistry();
        listener = new WorkItemFlowEventListener();
        // Inject registry via reflection (field injection — set directly for unit test)
        try {
            var field = WorkItemFlowEventListener.class.getDeclaredField("registry");
            field.setAccessible(true);
            field.set(listener, registry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private WorkItemLifecycleEvent event(final String type, final UUID workItemId, final String detail) {
        final WorkItem wi = new WorkItem();
        wi.id = workItemId;
        wi.status = WorkItemStatus.COMPLETED;
        // The factory builds the full type prefix, but these tests pass the complete type string.
        // Extract the event suffix from the full type to use with the factory.
        final String suffix = type.substring(type.lastIndexOf('.') + 1);
        return WorkItemLifecycleEvent.of(suffix, wi, "actor", detail);
    }

    @Test
    void completedEvent_completesRegisteredFuture() throws Exception {
        UUID id = UUID.randomUUID();
        CompletableFuture<String> future = registry.register(id);
        listener.onWorkItemEvent(event("io.quarkiverse.workitems.workitem.completed", id, "{\"ok\":true}"));
        assertThat(future.get()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void rejectedEvent_failsRegisteredFuture() {
        UUID id = UUID.randomUUID();
        CompletableFuture<String> future = registry.register(id);
        listener.onWorkItemEvent(event("io.quarkiverse.workitems.workitem.rejected", id, "out of scope"));
        assertThat(future.isCompletedExceptionally()).isTrue();
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(WorkItemResolutionException.class)
                .hasMessageContaining("out of scope");
    }

    @Test
    void cancelledEvent_failsRegisteredFuture() {
        UUID id = UUID.randomUUID();
        CompletableFuture<String> future = registry.register(id);
        listener.onWorkItemEvent(event("io.quarkiverse.workitems.workitem.cancelled", id, null));
        assertThat(future.isCompletedExceptionally()).isTrue();
    }

    @Test
    void createdEvent_ignored() {
        UUID id = UUID.randomUUID();
        CompletableFuture<String> future = registry.register(id);
        listener.onWorkItemEvent(event("io.quarkiverse.workitems.workitem.created", id, null));
        assertThat(future.isDone()).isFalse();
    }

    @Test
    void completedEvent_unknownWorkItemId_noException() {
        // WorkItem not from a flow — should silently ignore
        assertThatCode(() -> listener.onWorkItemEvent(event("io.quarkiverse.workitems.workitem.completed",
                UUID.randomUUID(), "resolution"))).doesNotThrowAnyException();
    }

    @Test
    void rejectedEvent_nullDetail_usesDefaultMessage() {
        UUID id = UUID.randomUUID();
        CompletableFuture<String> future = registry.register(id);
        listener.onWorkItemEvent(event("io.quarkiverse.workitems.workitem.rejected", id, null));
        assertThat(future.isCompletedExceptionally()).isTrue();
        assertThatThrownBy(future::get)
                .hasCauseInstanceOf(WorkItemResolutionException.class)
                .hasMessageContaining("rejected");
    }
}
