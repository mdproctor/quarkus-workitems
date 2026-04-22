package io.quarkiverse.workitems.mongodb;

import java.util.List;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import org.bson.Document;

import io.quarkiverse.workitems.runtime.model.AuditEntry;
import io.quarkiverse.workitems.runtime.repository.AuditEntryStore;
import io.quarkiverse.workitems.runtime.repository.AuditQuery;

/**
 * MongoDB implementation of {@link AuditEntryStore}.
 *
 * <p>
 * Selected by CDI over the default {@code JpaAuditEntryStore} when this module is on
 * the classpath. Audit entries are append-only: documents are never updated or deleted.
 */
@ApplicationScoped
@Alternative
@Priority(1)
public class MongoAuditEntryStore implements AuditEntryStore {

    @Override
    public void append(final AuditEntry entry) {
        MongoAuditEntryDocument.from(entry).persist();
    }

    @Override
    public List<AuditEntry> findByWorkItemId(final UUID workItemId) {
        return MongoAuditEntryDocument
                .<MongoAuditEntryDocument> find(new Document("workItemId", workItemId.toString()))
                .list()
                .stream()
                .map(MongoAuditEntryDocument::toDomain)
                .toList();
    }

    @Override
    public List<AuditEntry> query(final AuditQuery query) {
        return MongoAuditEntryDocument
                .<MongoAuditEntryDocument> find(buildFilter(query))
                .page(query.page(), query.size())
                .list()
                .stream()
                .map(MongoAuditEntryDocument::toDomain)
                .toList();
    }

    @Override
    public long count(final AuditQuery query) {
        return MongoAuditEntryDocument.count(buildFilter(query));
    }

    private Document buildFilter(final AuditQuery query) {
        final Document filter = new Document();
        if (query.actorId() != null) {
            filter.append("actor", query.actorId());
        }
        if (query.event() != null) {
            filter.append("event", query.event());
        }
        if (query.from() != null || query.to() != null) {
            final Document dateFilter = new Document();
            if (query.from() != null) {
                dateFilter.append("$gte", query.from());
            }
            if (query.to() != null) {
                dateFilter.append("$lte", query.to());
            }
            filter.append("occurredAt", dateFilter);
        }
        // category requires cross-collection lookup — not supported in MongoDB store
        return filter;
    }
}
