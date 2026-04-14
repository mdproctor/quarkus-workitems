package io.quarkiverse.tarkus.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PendingWorkItemRegistryTest {

    private PendingWorkItemRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PendingWorkItemRegistry();
    }

    @Test
    void register_returnsIncompleteFuture() {
        CompletableFuture<String> future = registry.register(UUID.randomUUID());
        assertThat(future.isDone()).isFalse();
    }

    @Test
    void register_isPending() {
        UUID id = UUID.randomUUID();
        registry.register(id);
        assertThat(registry.isPending(id)).isTrue();
    }

    @Test
    void complete_futureResolvesWithResolution() throws Exception {
        UUID id = UUID.randomUUID();
        CompletableFuture<String> future = registry.register(id);
        registry.complete(id, "{\"decision\":\"approved\"}");
        assertThat(future.isDone()).isTrue();
        assertThat(future.get()).isEqualTo("{\"decision\":\"approved\"}");
    }

    @Test
    void complete_removesFromPending() throws Exception {
        UUID id = UUID.randomUUID();
        registry.register(id);
        registry.complete(id, "done");
        assertThat(registry.isPending(id)).isFalse();
    }

    @Test
    void complete_unknownId_noException() {
        assertThatCode(() -> registry.complete(UUID.randomUUID(), "done"))
                .doesNotThrowAnyException();
    }

    @Test
    void fail_futureCompletesExceptionally() {
        UUID id = UUID.randomUUID();
        CompletableFuture<String> future = registry.register(id);
        registry.fail(id, "rejected: not approved");
        assertThat(future.isCompletedExceptionally()).isTrue();
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(WorkItemResolutionException.class)
                .hasMessageContaining("not approved");
    }

    @Test
    void fail_unknownId_noException() {
        assertThatCode(() -> registry.fail(UUID.randomUUID(), "reason"))
                .doesNotThrowAnyException();
    }

    @Test
    void pendingCount_tracksRegistrations() {
        registry.register(UUID.randomUUID());
        registry.register(UUID.randomUUID());
        assertThat(registry.pendingCount()).isEqualTo(2);
    }

    @Test
    void multipleFutures_completeIndependently() throws Exception {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        CompletableFuture<String> f1 = registry.register(id1);
        CompletableFuture<String> f2 = registry.register(id2);
        registry.complete(id1, "resolution-1");
        assertThat(f1.isDone()).isTrue();
        assertThat(f2.isDone()).isFalse();
        registry.fail(id2, "rejected");
        assertThat(f2.isCompletedExceptionally()).isTrue();
    }

    @Test
    void complete_withNullResolution_futureCompletesWithNull() throws Exception {
        UUID id = UUID.randomUUID();
        CompletableFuture<String> future = registry.register(id);
        registry.complete(id, null);
        assertThat(future.isDone()).isTrue();
        assertThat(future.get()).isNull();
    }
}
