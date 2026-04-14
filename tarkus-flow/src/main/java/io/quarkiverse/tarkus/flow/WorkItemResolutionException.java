package io.quarkiverse.tarkus.flow;

import java.util.UUID;

/** Thrown when a WorkItem is rejected or cancelled instead of completed. */
public class WorkItemResolutionException extends RuntimeException {

    private final UUID workItemId;

    public WorkItemResolutionException(final UUID workItemId, final String reason) {
        super("WorkItem " + workItemId + " could not be resolved: " + reason);
        this.workItemId = workItemId;
    }

    public UUID getWorkItemId() {
        return workItemId;
    }
}
