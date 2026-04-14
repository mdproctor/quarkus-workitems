package io.quarkiverse.tarkus.runtime.service;

import java.util.UUID;

/**
 * Thrown when a requested {@link io.quarkiverse.tarkus.runtime.model.WorkItem}
 * cannot be found by its UUID primary key.
 *
 * <p>
 * The REST layer maps this to an HTTP 404 response.
 */
public class WorkItemNotFoundException extends RuntimeException {

    /**
     * Constructs a new exception with a message identifying the missing WorkItem.
     *
     * @param id the UUID of the WorkItem that could not be found
     */
    public WorkItemNotFoundException(final UUID id) {
        super("WorkItem not found: " + id);
    }
}
