package io.quarkiverse.workitems.runtime.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.workitems.runtime.config.WorkItemsConfig;
import io.quarkiverse.workitems.runtime.event.WorkItemLifecycleEvent;
import io.quarkiverse.workitems.runtime.model.AuditEntry;
import io.quarkiverse.workitems.runtime.model.DelegationState;
import io.quarkiverse.workitems.runtime.model.LabelPersistence;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.workitems.runtime.model.WorkItemLabel;
import io.quarkiverse.workitems.runtime.model.WorkItemPriority;
import io.quarkiverse.workitems.runtime.model.WorkItemStatus;
import io.quarkiverse.workitems.runtime.repository.AuditEntryStore;
import io.quarkiverse.workitems.runtime.repository.WorkItemStore;
import io.quarkiverse.workitems.spi.AssignmentTrigger;

@ApplicationScoped
public class WorkItemService {

    private final WorkItemStore workItemStore;
    private final AuditEntryStore auditStore;
    private final WorkItemsConfig config;
    private final WorkItemAssignmentService assignmentService;

    @Inject
    Event<WorkItemLifecycleEvent> lifecycleEvent;

    @Inject
    public WorkItemService(final WorkItemStore workItemStore,
            final AuditEntryStore auditStore,
            final WorkItemsConfig config,
            final WorkItemAssignmentService assignmentService) {
        this.workItemStore = workItemStore;
        this.auditStore = auditStore;
        this.config = config;
        this.assignmentService = assignmentService;
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
        item.confidenceScore = request.confidenceScore();
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

        // Labels: only MANUAL labels accepted at creation time
        if (request.labels() != null) {
            for (var labelReq : request.labels()) {
                if (labelReq.persistence() == LabelPersistence.INFERRED) {
                    throw new IllegalArgumentException(
                            "INFERRED labels cannot be submitted at creation time — they are managed by the filter engine");
                }
                item.labels.add(new WorkItemLabel(labelReq.path(), labelReq.persistence(), labelReq.appliedBy()));
            }
        }

        assignmentService.assign(item, AssignmentTrigger.CREATED);
        final WorkItem saved = workItemStore.put(item);
        audit(saved.id, "CREATED", request.createdBy(), null);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("CREATED", saved, request.createdBy(), null));
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
        final WorkItem saved = workItemStore.put(item);
        audit(saved.id, "ASSIGNED", claimantId, null);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("ASSIGNED", saved, claimantId, null));
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
        final WorkItem saved = workItemStore.put(item);
        audit(saved.id, "STARTED", actorId, null);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("STARTED", saved, actorId, null));
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
        final WorkItem saved = workItemStore.put(item);
        audit(saved.id, "COMPLETED", actorId, null);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("COMPLETED", saved, actorId, resolution));
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
        final WorkItem saved = workItemStore.put(item);
        audit(saved.id, "REJECTED", actorId, reason);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("REJECTED", saved, actorId, reason));
        }
        return saved;
    }

    /**
     * Complete a WorkItem with an explicit rationale and policy reference for ledger capture.
     *
     * @param id the WorkItem UUID
     * @param actorId who completed it
     * @param resolution the resolution payload
     * @param rationale the actor's stated basis for the decision (GDPR Art. 22 compliance)
     * @param planRef the policy/procedure version that governed this decision
     */
    @Transactional
    public WorkItem complete(final UUID id, final String actorId, final String resolution,
            final String rationale, final String planRef) {
        final WorkItem item = requireWorkItem(id);
        if (item.status != WorkItemStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot complete WorkItem in status: " + item.status);
        }
        item.status = WorkItemStatus.COMPLETED;
        item.completedAt = Instant.now();
        item.resolution = resolution;
        final WorkItem saved = workItemStore.put(item);
        audit(saved.id, "COMPLETED", actorId, null);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of(
                    "COMPLETED", saved, actorId, resolution, rationale, planRef));
        }
        return saved;
    }

    /**
     * Reject a WorkItem with an explicit rationale for ledger capture.
     *
     * @param id the WorkItem UUID
     * @param actorId who rejected it
     * @param reason the rejection reason (stored as event detail)
     * @param rationale the actor's formal stated basis (stored as ledger rationale)
     */
    @Transactional
    public WorkItem reject(final UUID id, final String actorId, final String reason,
            final String rationale) {
        final WorkItem item = requireWorkItem(id);
        if (item.status != WorkItemStatus.ASSIGNED && item.status != WorkItemStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot reject WorkItem in status: " + item.status);
        }
        item.status = WorkItemStatus.REJECTED;
        item.completedAt = Instant.now();
        final WorkItem saved = workItemStore.put(item);
        audit(saved.id, "REJECTED", actorId, reason);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of(
                    "REJECTED", saved, actorId, reason, rationale, null));
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
        item.delegationState = DelegationState.PENDING;
        // Fire strategy while item is still in its current state (assigneeId/status
        // unchanged) so countActive sees the existing load correctly before reassignment.
        assignmentService.assign(item, AssignmentTrigger.DELEGATED);
        // If strategy did not select a candidate, fall back to the explicit 'to' param.
        if (item.assigneeId == null || item.assigneeId.equals(actorId)) {
            item.assigneeId = toAssigneeId;
            item.status = WorkItemStatus.PENDING;
        }
        final WorkItem saved = workItemStore.put(item);
        audit(saved.id, "DELEGATED", actorId, "to:" + saved.assigneeId);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("DELEGATED", saved, actorId, "to:" + toAssigneeId));
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
        assignmentService.assign(item, AssignmentTrigger.RELEASED);
        final WorkItem saved = workItemStore.put(item);
        audit(saved.id, "RELEASED", actorId, null);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("RELEASED", saved, actorId, null));
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
        final WorkItem saved = workItemStore.put(item);
        audit(saved.id, "SUSPENDED", actorId, reason);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("SUSPENDED", saved, actorId, reason));
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
        final WorkItem saved = workItemStore.put(item);
        audit(saved.id, "RESUMED", actorId, null);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("RESUMED", saved, actorId, null));
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
        final WorkItem saved = workItemStore.put(item);
        audit(saved.id, "CANCELLED", actorId, reason);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("CANCELLED", saved, actorId, reason));
        }
        return saved;
    }

    @Transactional
    public WorkItem addLabel(final UUID workItemId, final String path, final String appliedBy) {
        final WorkItem item = workItemStore.get(workItemId)
                .orElseThrow(() -> new WorkItemNotFoundException(workItemId));
        item.labels.add(new WorkItemLabel(path, LabelPersistence.MANUAL, appliedBy));
        final WorkItem saved = workItemStore.put(item);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("LABEL_ADDED", saved, appliedBy, null));
        }
        return saved;
    }

    @Transactional
    public WorkItem removeLabel(final UUID workItemId, final String path) {
        final WorkItem item = workItemStore.get(workItemId)
                .orElseThrow(() -> new WorkItemNotFoundException(workItemId));
        final boolean removed = item.labels.removeIf(
                l -> l.path.equals(path) && l.persistence == LabelPersistence.MANUAL);
        if (!removed) {
            throw new LabelNotFoundException(workItemId, path);
        }
        final WorkItem saved = workItemStore.put(item);
        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("LABEL_REMOVED", saved, "system", path));
        }
        return saved;
    }

    /**
     * Clone a WorkItem — creates a new PENDING WorkItem copying operational fields from the source.
     *
     * <p>
     * <strong>Copied:</strong> title (optionally overridden), description, category, formKey, priority,
     * candidateGroups, candidateUsers, requiredCapabilities, payload, MANUAL labels.
     *
     * <p>
     * <strong>Not copied:</strong> id, status (always PENDING), assigneeId, owner, delegationState,
     * delegationChain, priorStatus, resolution, all timestamps, INFERRED labels (the filter engine
     * re-applies them on the first lifecycle event).
     *
     * @param sourceId the WorkItem to clone
     * @param titleOverride if non-null and non-blank, used as the clone's title; otherwise appends " (copy)"
     * @param createdBy the actor creating the clone
     * @return the newly created PENDING WorkItem
     * @throws WorkItemNotFoundException if the source WorkItem does not exist
     */
    @Transactional
    public WorkItem clone(final UUID sourceId, final String titleOverride, final String createdBy) {
        final WorkItem source = workItemStore.get(sourceId)
                .orElseThrow(() -> new WorkItemNotFoundException(sourceId));

        final String title = (titleOverride != null && !titleOverride.isBlank())
                ? titleOverride
                : source.title + " (copy)";

        final java.util.List<WorkItemLabel> manualLabels = source.labels == null
                ? java.util.List.of()
                : source.labels.stream()
                        .filter(l -> l.persistence == LabelPersistence.MANUAL)
                        .toList();

        final WorkItemCreateRequest req = new WorkItemCreateRequest(
                title, source.description, source.category, source.formKey,
                source.priority, null, source.candidateGroups, source.candidateUsers,
                source.requiredCapabilities, createdBy, source.payload,
                null, null, null, null, null);

        WorkItem clone = create(req);

        for (final WorkItemLabel label : manualLabels) {
            clone = addLabel(clone.id, label.path, label.appliedBy);
        }

        return clone;
    }

    private WorkItem requireWorkItem(final UUID id) {
        return workItemStore.get(id)
                .orElseThrow(() -> new WorkItemNotFoundException(id));
    }

    private void audit(final UUID workItemId, final String event, final String actor, final String detail) {
        final AuditEntry entry = new AuditEntry();
        entry.workItemId = workItemId;
        entry.event = event;
        entry.actor = actor;
        entry.detail = detail;
        entry.occurredAt = Instant.now();
        auditStore.append(entry);
    }
}
