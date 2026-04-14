package io.quarkiverse.tarkus.runtime.repository;

import java.util.List;
import java.util.UUID;

import io.quarkiverse.tarkus.runtime.model.AuditEntry;

/**
 * Repository SPI for appending and querying {@link AuditEntry} records.
 *
 * <p>
 * Audit entries are immutable once written. Implementations must never
 * update or delete existing entries.
 */
public interface AuditEntryRepository {

    /**
     * Append an audit entry to the log. The entry is immutable once written.
     *
     * @param entry the audit entry to persist; must not be {@code null}
     */
    void append(AuditEntry entry);

    /**
     * Return all audit entries for the given WorkItem in chronological order.
     *
     * @param workItemId the UUID of the {@link io.quarkiverse.tarkus.runtime.model.WorkItem}
     *        whose audit trail is requested
     * @return list of audit entries ordered by {@code occurredAt} ascending;
     *         empty list if the work item has no recorded events
     */
    List<AuditEntry> findByWorkItemId(UUID workItemId);
}
