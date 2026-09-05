package io.casehub.work.api.spi;

import io.casehub.work.api.DeclineTarget;
import io.casehub.work.api.WorkItem;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.WorkItemQuery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkItemOperations {

    WorkItem create(WorkItemCreateRequest request);

    WorkItem claim(UUID id, String claimantId);

    WorkItem start(UUID id, String actorId);

    WorkItem complete(UUID id, String actorId, String resolution, String outcome);

    WorkItem complete(UUID id, String actorId, String resolution, String outcome, String rationale, String planRef);

    WorkItem completeFromSystem(UUID id, String actorId, String resolution);

    WorkItem reject(UUID id, String actorId, String reason, String outcome);

    WorkItem reject(UUID id, String actorId, String reason, String outcome, String rationale);

    WorkItem rejectFromSystem(UUID id, String actorId, String reason);

    WorkItem delegate(UUID id, String actorId, String toAssigneeId, DeclineTarget declineTarget);

    WorkItem acceptDelegation(UUID id, String claimantId);

    WorkItem declineDelegation(UUID id, String actorId);

    WorkItem release(UUID id, String actorId);

    WorkItem suspend(UUID id, String actorId, String reason);

    WorkItem resume(UUID id, String actorId);

    WorkItem cancel(UUID id, String actorId, String reason);

    WorkItem cancelFromSystem(UUID id, String actorId, String reason);

    WorkItem fault(UUID id, String systemActorId, String errorDetail);

    WorkItem faultFromSystem(UUID id, String actorId, String errorDetail);

    WorkItem obsolete(UUID id, String triggeredBy, String reason);

    WorkItem obsoleteFromSystem(UUID id, String triggeredBy, String reason);

    WorkItem escalate(UUID id, String actor, String targetGroup, String reason);

    WorkItem extend(UUID id, Instant newExpiresAt, String actorId);

    WorkItem updateDeadline(UUID id, Instant newDeadline, String actorId);

    WorkItem addLabel(UUID workItemId, String path, String appliedBy);

    WorkItem removeLabel(UUID workItemId, String path);

    WorkItem clone(UUID sourceId, String titleOverride, String createdBy);

    Optional<WorkItem> findById(UUID id);

    List<WorkItem> scan(WorkItemQuery query);

    List<WorkItem> findChildrenByParentId(UUID parentId);

    Optional<WorkItem> findByCallerRef(String callerRef);

    Optional<WorkItem> findActiveByCallerRef(String callerRef);

    WorkItem compensate(UUID originalId, WorkItemCreateRequest request, String triggeredBy, String reason);

    WorkItem markCompensated(UUID originalId);

}
