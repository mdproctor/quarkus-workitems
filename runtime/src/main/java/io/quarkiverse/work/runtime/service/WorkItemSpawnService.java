package io.quarkiverse.work.runtime.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.work.api.ChildSpec;
import io.quarkiverse.work.api.SpawnPort;
import io.quarkiverse.work.api.SpawnRequest;
import io.quarkiverse.work.api.SpawnResult;
import io.quarkiverse.work.api.SpawnedChild;
import io.quarkiverse.work.runtime.event.WorkItemLifecycleEvent;
import io.quarkiverse.work.runtime.model.AuditEntry;
import io.quarkiverse.work.runtime.model.WorkItem;
import io.quarkiverse.work.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.work.runtime.model.WorkItemRelation;
import io.quarkiverse.work.runtime.model.WorkItemRelationType;
import io.quarkiverse.work.runtime.model.WorkItemSpawnGroup;
import io.quarkiverse.work.runtime.model.WorkItemTemplate;
import io.quarkiverse.work.runtime.repository.AuditEntryStore;
import io.quarkiverse.work.runtime.repository.WorkItemStore;

/**
 * Implements {@link SpawnPort} for the WorkItem domain.
 *
 * <p>
 * Spawning creates a group of child WorkItems from a set of templates, wires
 * {@link WorkItemRelationType#PART_OF} relations from each child to the parent,
 * persists a {@link WorkItemSpawnGroup} for idempotency tracking, and fires a
 * {@code SPAWNED} lifecycle event on the parent.
 *
 * <p>
 * Idempotency: if a spawn call is retried with the same {@code (parentId, idempotencyKey)},
 * the existing group and children are returned with {@code created=false} — no duplicates created.
 */
@ApplicationScoped
public class WorkItemSpawnService implements SpawnPort {

    private final WorkItemStore workItemStore;
    private final WorkItemService workItemService;
    private final AuditEntryStore auditStore;

    @Inject
    Event<WorkItemLifecycleEvent> lifecycleEvent;

    @Inject
    public WorkItemSpawnService(final WorkItemStore workItemStore, final WorkItemService workItemService,
            final AuditEntryStore auditStore) {
        this.workItemStore = workItemStore;
        this.workItemService = workItemService;
        this.auditStore = auditStore;
    }

    /**
     * Spawn a group of child WorkItems from a parent.
     *
     * @param request the spawn request containing parentId, idempotencyKey, and child specs
     * @return result with group ID, spawned children, and created flag
     * @throws IllegalArgumentException if children is empty or idempotencyKey is blank
     * @throws WorkItemNotFoundException if the parent WorkItem does not exist
     * @throws IllegalStateException if the parent WorkItem is in a terminal status
     */
    @Override
    @Transactional
    public SpawnResult spawn(final SpawnRequest request) {
        if (request.children() == null || request.children().isEmpty()) {
            throw new IllegalArgumentException("children must not be empty");
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }

        final WorkItem parent = workItemStore.get(request.parentId())
                .orElseThrow(() -> new WorkItemNotFoundException(request.parentId()));

        if (parent.status.isTerminal()) {
            throw new IllegalStateException(
                    "Cannot spawn children from parent WorkItem in terminal status: " + parent.status);
        }

        // Idempotency check
        final WorkItemSpawnGroup existing = WorkItemSpawnGroup.findByParentAndKey(
                request.parentId(), request.idempotencyKey());
        if (existing != null) {
            final String createdByMarker = "system:spawn:" + existing.id;
            final List<SpawnedChild> existingChildren = WorkItemRelation
                    .findByTargetAndType(request.parentId(), WorkItemRelationType.PART_OF)
                    .stream()
                    .filter(r -> createdByMarker.equals(r.createdBy))
                    .map(r -> {
                        final WorkItem child = workItemStore.get(r.sourceId).orElseThrow();
                        return new SpawnedChild(child.id, child.callerRef);
                    })
                    .toList();
            return new SpawnResult(existing.id, existingChildren, false);
        }

        // Create the spawn group for idempotency
        final WorkItemSpawnGroup group = new WorkItemSpawnGroup();
        group.parentId = request.parentId();
        group.idempotencyKey = request.idempotencyKey();
        group.persist();

        // Spawn each child
        final List<SpawnedChild> spawnedChildren = new ArrayList<>();
        for (final ChildSpec spec : request.children()) {
            final WorkItemTemplate template = WorkItemTemplate.findById(spec.templateId());
            if (template == null) {
                throw new IllegalArgumentException("WorkItemTemplate not found: " + spec.templateId());
            }

            final WorkItemCreateRequest createRequest = new WorkItemCreateRequest(
                    template.name,
                    template.description,
                    template.category,
                    null, // formKey — not on template
                    template.priority,
                    null, // assigneeId
                    override(spec, "candidateGroups", template.candidateGroups),
                    override(spec, "candidateUsers", template.candidateUsers),
                    override(spec, "requiredCapabilities", template.requiredCapabilities),
                    "system:spawn:" + group.id,
                    template.defaultPayload,
                    null, // claimDeadline
                    null, // expiresAt
                    null, // followUpDate
                    null, // labels
                    null, // confidenceScore
                    spec.callerRef(),
                    template.defaultClaimBusinessHours,
                    template.defaultExpiryBusinessHours);

            final WorkItem child = workItemService.create(createRequest);

            // Wire PART_OF relation: child → parent
            final WorkItemRelation relation = new WorkItemRelation();
            relation.sourceId = child.id;
            relation.targetId = request.parentId();
            relation.relationType = WorkItemRelationType.PART_OF;
            relation.createdBy = "system:spawn:" + group.id;
            relation.persist();

            spawnedChildren.add(new SpawnedChild(child.id, spec.callerRef()));
        }

        // Write SPAWNED audit entry on parent and fire CDI event
        final String spawnDetail = "groupId:" + group.id + ",children:" + spawnedChildren.size();
        final AuditEntry auditEntry = new AuditEntry();
        auditEntry.workItemId = parent.id;
        auditEntry.event = "SPAWNED";
        auditEntry.actor = "system:spawn";
        auditEntry.detail = spawnDetail;
        auditEntry.occurredAt = Instant.now();
        auditStore.append(auditEntry);

        if (lifecycleEvent != null) {
            lifecycleEvent.fire(WorkItemLifecycleEvent.of("SPAWNED", parent, "system:spawn", spawnDetail));
        }

        return new SpawnResult(group.id, spawnedChildren, true);
    }

    /**
     * Cancel a spawn group, optionally cascading cancellation to PENDING children.
     *
     * @param groupId the spawn group UUID
     * @param cascadeChildren if true, cancel all PENDING children with a PART_OF relation to the parent
     * @throws WorkItemNotFoundException if the spawn group does not exist
     */
    @Override
    @Transactional
    public void cancelGroup(final UUID groupId, final boolean cascadeChildren) {
        final WorkItemSpawnGroup group = WorkItemSpawnGroup.findById(groupId);
        if (group == null) {
            throw new WorkItemNotFoundException(groupId);
        }

        if (cascadeChildren) {
            final String createdByMarker = "system:spawn:" + groupId;
            WorkItemRelation.findByTargetAndType(group.parentId, WorkItemRelationType.PART_OF)
                    .stream()
                    .filter(r -> createdByMarker.equals(r.createdBy))
                    .forEach(r -> workItemStore.get(r.sourceId).ifPresent(child -> {
                        if (child.status == io.quarkiverse.work.runtime.model.WorkItemStatus.PENDING) {
                            workItemService.cancel(child.id, "system:spawn",
                                    "Cancelled by spawn group cancellation");
                        }
                    }));
        }

        group.delete();
    }

    /**
     * Returns the override value for {@code key} from the spec's overrides map,
     * or {@code templateValue} when no override is present.
     *
     * @param spec the ChildSpec that may carry overrides
     * @param key the field name to look up in the overrides map
     * @param templateValue the template default to fall back to
     * @return the override as a String, null if the override is explicitly null, or templateValue
     */
    private String override(final ChildSpec spec, final String key, final String templateValue) {
        if (spec.overrides() != null && spec.overrides().containsKey(key)) {
            final Object val = spec.overrides().get(key);
            return val != null ? val.toString() : null;
        }
        return templateValue;
    }
}
