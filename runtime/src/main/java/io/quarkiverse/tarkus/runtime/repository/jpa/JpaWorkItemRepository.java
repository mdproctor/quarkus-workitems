package io.quarkiverse.tarkus.runtime.repository.jpa;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.tarkus.runtime.model.WorkItem;
import io.quarkiverse.tarkus.runtime.model.WorkItemPriority;
import io.quarkiverse.tarkus.runtime.model.WorkItemStatus;
import io.quarkiverse.tarkus.runtime.repository.WorkItemRepository;

@ApplicationScoped
public class JpaWorkItemRepository implements WorkItemRepository {

    @Override
    public WorkItem save(WorkItem workItem) {
        workItem.persistAndFlush();
        return workItem;
    }

    @Override
    public Optional<WorkItem> findById(UUID id) {
        return Optional.ofNullable(WorkItem.findById(id));
    }

    @Override
    public List<WorkItem> findAll() {
        return WorkItem.listAll();
    }

    @Override
    public List<WorkItem> findInbox(String assignee, List<String> candidateGroups,
            WorkItemStatus status, WorkItemPriority priority,
            String category, Instant followUpBefore) {

        final StringBuilder assignment = new StringBuilder("(1=0");
        final Map<String, Object> params = new HashMap<>();

        if (assignee != null) {
            assignment.append(" OR wi.assigneeId = :assignee OR wi.candidateUsers LIKE :assigneeLike");
            params.put("assignee", assignee);
            params.put("assigneeLike", "%" + assignee + "%");
        }
        if (candidateGroups != null) {
            for (int i = 0; i < candidateGroups.size(); i++) {
                final String key = "group" + i;
                assignment.append(" OR wi.candidateGroups LIKE :").append(key);
                params.put(key, "%" + candidateGroups.get(i) + "%");
            }
        }
        assignment.append(")");

        final StringBuilder filters = new StringBuilder();
        if (status != null) {
            filters.append(" AND wi.status = :status");
            params.put("status", status);
        }
        if (priority != null) {
            filters.append(" AND wi.priority = :priority");
            params.put("priority", priority);
        }
        if (category != null) {
            filters.append(" AND wi.category = :category");
            params.put("category", category);
        }
        if (followUpBefore != null) {
            filters.append(" AND wi.followUpDate <= :followUpBefore");
            params.put("followUpBefore", followUpBefore);
        }

        final String query = assignment + filters.toString();
        return WorkItem.find(query, params).list();
    }

    @Override
    public List<WorkItem> findExpired(Instant now) {
        return WorkItem.find(
                "expiresAt <= ?1 AND status IN (?2, ?3, ?4, ?5)",
                now,
                WorkItemStatus.PENDING, WorkItemStatus.ASSIGNED,
                WorkItemStatus.IN_PROGRESS, WorkItemStatus.SUSPENDED).list();
    }

    @Override
    public List<WorkItem> findUnclaimedPastDeadline(Instant now) {
        return WorkItem.find(
                "claimDeadline <= ?1 AND status = ?2",
                now, WorkItemStatus.PENDING).list();
    }
}
