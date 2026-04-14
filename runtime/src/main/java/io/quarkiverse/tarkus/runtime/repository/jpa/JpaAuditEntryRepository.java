package io.quarkiverse.tarkus.runtime.repository.jpa;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.tarkus.runtime.model.AuditEntry;
import io.quarkiverse.tarkus.runtime.repository.AuditEntryRepository;

@ApplicationScoped
public class JpaAuditEntryRepository implements AuditEntryRepository {

    @Override
    public void append(AuditEntry entry) {
        entry.persist();
    }

    @Override
    public List<AuditEntry> findByWorkItemId(UUID workItemId) {
        return AuditEntry.list("workItemId = ?1 ORDER BY occurredAt ASC", workItemId);
    }
}
