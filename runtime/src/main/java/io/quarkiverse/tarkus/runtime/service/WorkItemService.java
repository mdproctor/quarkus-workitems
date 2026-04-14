package io.quarkiverse.tarkus.runtime.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.tarkus.runtime.config.TarkusConfig;
import io.quarkiverse.tarkus.runtime.event.WorkItemLifecycleEvent;
import io.quarkiverse.tarkus.runtime.model.AuditEntry;
import io.quarkiverse.tarkus.runtime.model.DelegationState;
import io.quarkiverse.tarkus.runtime.model.WorkItem;
import io.quarkiverse.tarkus.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.tarkus.runtime.model.WorkItemPriority;
import io.quarkiverse.tarkus.runtime.model.WorkItemStatus;
import io.quarkiverse.tarkus.runtime.repository.AuditEntryRepository;
import io.quarkiverse.tarkus.runtime.repository.WorkItemRepository;

@ApplicationScoped
public class WorkItemService {

    private final WorkItemRepository workItemRepo;
    private final AuditEntryRepository auditRepo;
    private final TarkusConfig config;

    @Inject
    Event<WorkItemLifecycleEvent> lifecycleEvent;

    @Inject
    public WorkItemService(final WorkItemRepository workItemRepo,
            final AuditEntryRepository auditRepo,
            final TarkusConfig config) {
        this.workItemRepo = workItemRepo;
        this.auditRepo = auditRepo;
        this.config = config;
    }

    @Transactional
    public WorkItem create(final WorkItemCreateRequest request) {
        final WorkItem item = new WorkItem();
        item.status = WorkItemStatus.PENDING;
        item.title = request.title();
        item.description = request.description();
        item.category = request.category();
        item.formKey = request.formKey();
        item.priority = request.priority() != null ? request.priority() : WorkItemPriority.NORMAL;
        item.assigneeId = request.assigneeId();
        item.candidateGroups = request.candidateGroups();
        item.candidateUsers = request.candidateUsers();
        item.requiredCapabilities = request.requiredCapabilities();
        item.createdBy = request.createdBy();
        item.payload = request.payload();
        item.followUpDate = request.followUpDate();

        final Instant now = Instant.now();
        item.createdAt = now;
        item.updatedAt = now;
        item.expiresAt = request.expiresAt() != null
                ? request.expiresAt()
                : now.plus(config.defaultExpiryHours(), ChronoUnit.HOURS);

        if (request.claimDeadline() != null) {
            item.claimDeadline = request.claimDeadline();
        } else if (config.defaultClaimHours() > 0) {
            item.claimDeadline = now.plus(config.defaultClaimHours(), ChronoUnit.HOURS);
        }

        final WorkItem saved = workItemRepo.save(item);
        audit(saved.id, "CREATED", request.createdBy(), null);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("CREATED", saved.id, saved.status, request.createdBy(), null));
        }
        return saved;
    }

    @Transactional
    public WorkItem claim(final UUID id, final String claimantId) {
        final WorkItem item = requireWorkItem(id);
        if (item.status != WorkItemStatus.PENDING) {
            throw new IllegalStateException("Cannot claim WorkItem in status: " + item.status);
        }
        item.status = WorkItemStatus.ASSIGNED;
        item.assigneeId = claimantId;
        item.assignedAt = Instant.now();
        final WorkItem saved = workItemRepo.save(item);
        audit(saved.id, "ASSIGNED", claimantId, null);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("ASSIGNED", saved.id, saved.status, claimantId, null));
        }
        return saved;
    }

    @Transactional
    public WorkItem start(final UUID id, final String actorId) {
        final WorkItem item = requireWorkItem(id);
        if (item.status != WorkItemStatus.ASSIGNED) {
            throw new IllegalStateException("Cannot start WorkItem in status: " + item.status);
        }
        item.status = WorkItemStatus.IN_PROGRESS;
        item.startedAt = Instant.now();
        final WorkItem saved = workItemRepo.save(item);
        audit(saved.id, "STARTED", actorId, null);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("STARTED", saved.id, saved.status, actorId, null));
        }
        return saved;
    }

    @Transactional
    public WorkItem complete(final UUID id, final String actorId, final String resolution) {
        final WorkItem item = requireWorkItem(id);
        if (item.status != WorkItemStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot complete WorkItem in status: " + item.status);
        }
        item.status = WorkItemStatus.COMPLETED;
        item.completedAt = Instant.now();
        item.resolution = resolution;
        final WorkItem saved = workItemRepo.save(item);
        audit(saved.id, "COMPLETED", actorId, null);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("COMPLETED", saved.id, saved.status, actorId, null));
        }
        return saved;
    }

    @Transactional
    public WorkItem reject(final UUID id, final String actorId, final String reason) {
        final WorkItem item = requireWorkItem(id);
        if (item.status != WorkItemStatus.ASSIGNED && item.status != WorkItemStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot reject WorkItem in status: " + item.status);
        }
        item.status = WorkItemStatus.REJECTED;
        item.completedAt = Instant.now();
        final WorkItem saved = workItemRepo.save(item);
        audit(saved.id, "REJECTED", actorId, reason);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("REJECTED", saved.id, saved.status, actorId, reason));
        }
        return saved;
    }

    @Transactional
    public WorkItem delegate(final UUID id, final String actorId, final String toAssigneeId) {
        final WorkItem item = requireWorkItem(id);
        if (item.status != WorkItemStatus.ASSIGNED && item.status != WorkItemStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot delegate WorkItem in status: " + item.status);
        }
        if (item.owner == null) {
            item.owner = actorId;
        }
        item.delegationChain = item.delegationChain == null
                ? actorId
                : item.delegationChain + "," + actorId;
        item.assigneeId = toAssigneeId;
        item.delegationState = DelegationState.PENDING;
        item.status = WorkItemStatus.PENDING;
        final WorkItem saved = workItemRepo.save(item);
        audit(saved.id, "DELEGATED", actorId, "to:" + toAssigneeId);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("DELEGATED", saved.id, saved.status, actorId, "to:" + toAssigneeId));
        }
        return saved;
    }

    @Transactional
    public WorkItem release(final UUID id, final String actorId) {
        final WorkItem item = requireWorkItem(id);
        if (item.status != WorkItemStatus.ASSIGNED) {
            throw new IllegalStateException("Cannot release WorkItem in status: " + item.status);
        }
        item.status = WorkItemStatus.PENDING;
        item.assigneeId = null;
        final WorkItem saved = workItemRepo.save(item);
        audit(saved.id, "RELEASED", actorId, null);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("RELEASED", saved.id, saved.status, actorId, null));
        }
        return saved;
    }

    @Transactional
    public WorkItem suspend(final UUID id, final String actorId, final String reason) {
        final WorkItem item = requireWorkItem(id);
        if (item.status != WorkItemStatus.ASSIGNED && item.status != WorkItemStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot suspend WorkItem in status: " + item.status);
        }
        item.priorStatus = item.status;
        item.status = WorkItemStatus.SUSPENDED;
        item.suspendedAt = Instant.now();
        final WorkItem saved = workItemRepo.save(item);
        audit(saved.id, "SUSPENDED", actorId, reason);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("SUSPENDED", saved.id, saved.status, actorId, reason));
        }
        return saved;
    }

    @Transactional
    public WorkItem resume(final UUID id, final String actorId) {
        final WorkItem item = requireWorkItem(id);
        if (item.status != WorkItemStatus.SUSPENDED) {
            throw new IllegalStateException("Cannot resume WorkItem in status: " + item.status);
        }
        item.status = item.priorStatus;
        item.priorStatus = null;
        item.suspendedAt = null;
        final WorkItem saved = workItemRepo.save(item);
        audit(saved.id, "RESUMED", actorId, null);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("RESUMED", saved.id, saved.status, actorId, null));
        }
        return saved;
    }

    @Transactional
    public WorkItem cancel(final UUID id, final String actorId, final String reason) {
        final WorkItem item = requireWorkItem(id);
        if (item.status.isTerminal()) {
            throw new IllegalStateException("Cannot cancel WorkItem in status: " + item.status);
        }
        item.status = WorkItemStatus.CANCELLED;
        item.completedAt = Instant.now();
        final WorkItem saved = workItemRepo.save(item);
        audit(saved.id, "CANCELLED", actorId, reason);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("CANCELLED", saved.id, saved.status, actorId, reason));
        }
        return saved;
    }

    private WorkItem requireWorkItem(final UUID id) {
        return workItemRepo.findById(id)
                .orElseThrow(() -> new WorkItemNotFoundException(id));
    }

    private void audit(final UUID workItemId, final String event, final String actor, final String detail) {
        final AuditEntry entry = new AuditEntry();
        entry.workItemId = workItemId;
        entry.event = event;
        entry.actor = actor;
        entry.detail = detail;
        entry.occurredAt = Instant.now();
        auditRepo.append(entry);
    }
}
