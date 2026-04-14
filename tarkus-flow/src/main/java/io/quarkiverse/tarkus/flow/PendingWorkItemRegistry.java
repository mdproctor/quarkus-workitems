package io.quarkiverse.tarkus.flow;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Tracks CompletableFutures awaiting human resolution of Tarkus WorkItems.
 * Thread-safe. When a WorkItem is completed or rejected, the corresponding
 * future is resolved so the suspended workflow can resume.
 */
@ApplicationScoped
public class PendingWorkItemRegistry {

    private final ConcurrentMap<UUID, CompletableFuture<String>> pending = new ConcurrentHashMap<>();

    /**
     * Register a new pending future for the given WorkItem.
     * Returns the future that will complete when the WorkItem is resolved.
     */
    public CompletableFuture<String> register(final UUID workItemId) {
        final CompletableFuture<String> future = new CompletableFuture<>();
        pending.put(workItemId, future);
        return future;
    }

    /**
     * Complete the future for the given WorkItem with the resolution JSON.
     * No-op if the workItemId is not registered (WorkItem not from this workflow).
     */
    public void complete(final UUID workItemId, final String resolution) {
        final CompletableFuture<String> future = pending.remove(workItemId);
        if (future != null) {
            future.complete(resolution);
        }
    }

    /**
     * Fail the future for the given WorkItem with the given reason.
     * No-op if the workItemId is not registered.
     */
    public void fail(final UUID workItemId, final String reason) {
        final CompletableFuture<String> future = pending.remove(workItemId);
        if (future != null) {
            future.completeExceptionally(new WorkItemResolutionException(workItemId, reason));
        }
    }

    /** Returns true if a future is pending for the given WorkItem. */
    public boolean isPending(final UUID workItemId) {
        return pending.containsKey(workItemId);
    }

    /** Returns the number of currently pending WorkItems. Useful for monitoring. */
    public int pendingCount() {
        return pending.size();
    }
}
