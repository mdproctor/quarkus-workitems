package io.quarkiverse.tarkus.testing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkiverse.tarkus.runtime.model.AuditEntry;
import io.quarkiverse.tarkus.runtime.repository.AuditEntryRepository;

/**
 * In-memory implementation of {@link AuditEntryRepository} for use in tests of
 * applications that embed Quarkus Tarkus. No datasource or Flyway configuration
 * is required.
 *
 * <p>
 * Activate by including {@code quarkus-tarkus-testing} on the test classpath. CDI
 * selects this bean over the default Panache implementation via {@code @Alternative}
 * and {@code @Priority(1)}.
 *
 * <p>
 * <strong>Not thread-safe</strong> — designed for single-threaded test use only.
 *
 * <p>
 * Call {@link #clear()} in a {@code @BeforeEach} method to isolate tests from one
 * another.
 */
@ApplicationScoped
@Alternative
@Priority(1)
public class InMemoryAuditEntryRepository implements AuditEntryRepository {

    // NOT thread-safe — designed for single-threaded test use
    private final List<AuditEntry> entries = new ArrayList<>();

    /**
     * Clears all stored entries. Call in {@code @BeforeEach} to isolate tests.
     */
    public void clear() {
        entries.clear();
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * If {@code entry.id} is {@code null} a fresh {@link UUID} is assigned. If
     * {@code entry.occurredAt} is {@code null} it is set to {@link Instant#now()}.
     */
    @Override
    public void append(final AuditEntry entry) {
        if (entry.id == null) {
            entry.id = UUID.randomUUID();
        }
        if (entry.occurredAt == null) {
            entry.occurredAt = Instant.now();
        }
        entries.add(entry);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<AuditEntry> findByWorkItemId(final UUID workItemId) {
        return entries.stream()
                .filter(e -> workItemId.equals(e.workItemId))
                .sorted(Comparator.comparing(e -> e.occurredAt))
                .toList();
    }
}
