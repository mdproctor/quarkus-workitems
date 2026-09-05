package io.casehub.work.runtime.service;

import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.platform.api.routing.StrategyResolver;
import io.casehub.work.api.AssignmentTrigger;
import io.casehub.work.api.CompensationStatus;
import io.casehub.work.api.ClaimSlaContext;
import io.casehub.work.api.DeclineTarget;
import io.casehub.work.api.LabelPersistence;
import io.casehub.work.api.PolicyDecision;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.WorkItemLifecycleEvent;
import io.casehub.work.api.WorkItemPriority;
import io.casehub.work.api.WorkItemQuery;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.api.spi.BusinessCalendar;
import io.casehub.work.api.spi.ClaimSlaPolicy;
import io.casehub.work.api.spi.ExclusionPolicy;
import io.casehub.work.api.spi.WorkItemOperations;
import io.casehub.work.api.spi.WorkItemStore;
import io.casehub.work.core.strategy.CapabilityValidator;
import io.casehub.work.runtime.config.WorkItemsConfig;
import io.casehub.work.runtime.event.WorkItemLifecycleEmitter;
import io.casehub.work.runtime.model.AuditEntry;
import io.casehub.work.runtime.model.WorkItemRelationType;
import io.casehub.work.runtime.model.WorkItemSpawnGroup;
import io.casehub.work.runtime.repository.AuditEntryStore;
import io.casehub.work.runtime.repository.WorkItemRelationStore;
import io.casehub.work.runtime.repository.WorkItemSpawnGroupStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class WorkItemService implements WorkItemOperations {

    private final WorkItemStore workItemStore;
    private final AuditEntryStore auditStore;
    private final WorkItemsConfig config;
    private final WorkItemAssignmentService assignmentService;
    private final ClaimSlaPolicy claimSlaPolicy;
    private final ExclusionPolicy exclusionPolicy;
    private final BlockedAttemptAuditService blockedAuditService;
    private final CapabilityValidator capabilityValidator;
    private final WorkItemTimerService timerService;
    // claimSlaPolicy is resolved at construction time via StrategyResolver

    @Inject
    WorkItemSpawnGroupStore spawnGroupStore;
    @Inject
    WorkItemRelationStore   relationStore;


    @Inject
    EntityManager em;

    @Inject
    FormSchemaValidationService schemaValidator;

    @Inject
    OutcomeValidator outcomeValidator;

    @Inject
    WorkItemLifecycleEmitter lifecycleEmitter;

    @Inject
    jakarta.enterprise.inject.Instance<BusinessCalendar> businessCalendar;

    @Inject
    PreferenceProvider preferenceProvider;

    @Inject
    public WorkItemService(final WorkItemStore workItemStore,
            final AuditEntryStore auditStore,
            final WorkItemsConfig config,
            final WorkItemAssignmentService assignmentService,
            final StrategyResolver strategyResolver,
            final ExclusionPolicy exclusionPolicy,
            final BlockedAttemptAuditService blockedAuditService,
            final CapabilityValidator capabilityValidator,
            final WorkItemTimerService timerService) {
        this.workItemStore = workItemStore;
        this.auditStore = auditStore;
        this.config = config;
        this.assignmentService = assignmentService;
        this.claimSlaPolicy = strategyResolver.resolve(ClaimSlaPolicy.class, config.sla().claimPolicy());
        this.exclusionPolicy = exclusionPolicy;
        this.blockedAuditService = blockedAuditService;
        this.capabilityValidator = capabilityValidator;
        this.timerService = timerService;
    }

    @Transactional
    public io.casehub.work.api.WorkItem create(final WorkItemCreateRequest request) {
        capabilityValidator.validate(CapabilityParser.parse(request.requiredCapabilities));

        final java.util.Set<String> types = new java.util.LinkedHashSet<>();
        if (request.types != null) {
            for (final String typePath : request.types) {
                Path.parse(typePath);
                types.add(typePath);
            }
        }

        final Instant now = Instant.now();

        // expiresAt: absolute > business hours > config default (wall-clock)
        final Instant expiresAt;
        if (request.expiresAt != null) {
            expiresAt = request.expiresAt;
        } else if (request.expiresAtBusinessHours != null) {
            expiresAt = resolveBusinessHours(now, request.expiresAtBusinessHours);
        } else {
            expiresAt = now.plus(config.defaultExpiryHours(), java.time.temporal.ChronoUnit.HOURS);
        }

        // claimDeadline: absolute > business hours > config default (wall-clock)
        final Instant claimDeadline;
        if (request.claimDeadline != null) {
            claimDeadline = request.claimDeadline;
        } else if (request.claimDeadlineBusinessHours != null) {
            claimDeadline = resolveBusinessHours(now, request.claimDeadlineBusinessHours);
        } else if (config.defaultClaimHours() > 0) {
            claimDeadline = now.plus(config.defaultClaimHours(), java.time.temporal.ChronoUnit.HOURS);
        } else {
            claimDeadline = null;
        }

        // Labels: only MANUAL labels accepted at creation time
        final List<io.casehub.work.api.WorkItemLabel> labels = new java.util.ArrayList<>();
        if (request.labels != null) {
            for (var labelReq : request.labels) {
                if (labelReq.persistence() == LabelPersistence.INFERRED) {
                    throw new IllegalArgumentException(
                            "INFERRED labels cannot be submitted at creation time — they are managed by the filter engine");
                }
                labels.add(new io.casehub.work.api.WorkItemLabel(labelReq.path(), labelReq.persistence(), labelReq.appliedBy()));
            }
        }

        if (request.inputDataSchema != null) {
            final List<String> violations = schemaValidator.validate(request.inputDataSchema, request.payload);
            if (!violations.isEmpty()) {
                throw new IllegalArgumentException("payload violates inputDataSchema: " + violations);
            }
        }

        final UUID id = UUID.randomUUID();

        if (request.assigneeId != null) {
            final PolicyDecision createDecision = exclusionPolicy.check(request.assigneeId, request.excludedUsers);
            if (createDecision.denied()) {
                blockedAuditService.record(id, "CREATE_DENIED", request.createdBy, createDecision.reason());
                throw new IllegalArgumentException(createDecision.reason());
            }
        }

        io.casehub.work.api.WorkItem item = io.casehub.work.api.WorkItem.builder()
                                                                        .id(id)
                                                                        .status(WorkItemStatus.PENDING)
                                                                        .title(request.title)
                                                                        .description(request.description)
                                                                        .types(types)
                                                                        .formKey(request.formKey)
                                                                        .priority(request.priority != null ? request.priority : WorkItemPriority.MEDIUM)
                                                                        .assigneeId(request.assigneeId)
                                                                        .candidateGroups(request.candidateGroups)
                                                                        .candidateUsers(request.candidateUsers)
                                                                        .requiredCapabilities(request.requiredCapabilities)
                                                                        .createdBy(request.createdBy)
                                                                        .payload(request.payload)
                                                                        .confidenceScore(request.confidenceScore)
                                                                        .callerRef(request.callerRef)
                                                                        .followUpDate(request.followUpDate)
                                                                        .templateId(request.templateId)
                                                                        .templateVersion(request.templateVersion)
                                                                        .permittedOutcomes(WorkItemTemplateService.encodeOutcomes(request.permittedOutcomes))
                                                                        .inputDataSchema(request.inputDataSchema)
                                                                        .outputDataSchema(request.outputDataSchema)
                                                                        .excludedUsers(request.excludedUsers)
                                                                        .scope(request.scope)
                                                                        .tenancyId(request.tenancyId)
                                                                        .payloadTypeName(request.payloadTypeName)
                                                                        .resolutionTypeName(request.resolutionTypeName)
                                                                        .candidateScores(request.candidateScores)
                                                                        .routingExperiences(request.routingExperiences)
                                                                        .originRef(request.originRef)
                                                                        .createdAt(now)
                                                                        .updatedAt(now)
                                                                        .expiresAt(expiresAt)
                                                                        .claimDeadline(claimDeadline)
                                                                        .accumulatedUnclaimedSeconds(0L)
                                                                        .lastReturnedToPoolAt(now)
                                                                        .labels(labels)
                                                                        .build();

        item = assignmentService.assign(item, AssignmentTrigger.CREATED);
        final io.casehub.work.api.WorkItem saved = workItemStore.put(item);
        if (saved.expiresAt() != null) {
            timerService.scheduleExpiry(saved.id(), saved.tenancyId(), saved.expiresAt());
        }
        if (saved.claimDeadline() != null) {
            timerService.scheduleClaimDeadline(saved.id(), saved.tenancyId(), saved.claimDeadline());
        }
        audit(saved.id(), "CREATED", request.createdBy, request.auditDetail);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("CREATED", saved, request.createdBy, null));
        return workItemStore.get(saved.id()).orElse(saved);
    }

    @Transactional
    public io.casehub.work.api.WorkItem claim(final UUID id, final String claimantId) {
        if (claimantId == null || claimantId.isBlank()) {
            throw new IllegalArgumentException("claimantId is required");
        }
        final io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.parentId() != null) {
            final WorkItemSpawnGroup group = spawnGroupStore.findMultiInstanceByParentId(item.parentId()).orElse(null);
            if (group != null) {
                em.detach(group);
                if (!group.allowSameAssignee) {
                    final long alreadyHeld = workItemStore.countByParentAndAssignee(item.parentId(), claimantId, id);
                    if (alreadyHeld > 0) {
                        throw new IllegalStateException(
                                "Claimant '" + claimantId + "' already hold another instance in this group");
                    }
                }
            }
        }
        if (item.status() != WorkItemStatus.PENDING) {
            throw new IllegalStateException("Cannot claim WorkItem in status: " + item.status());
        }
        final PolicyDecision claimDecision = exclusionPolicy.check(claimantId, item.excludedUsers());
        if (claimDecision.denied()) {
            blockedAuditService.record(item.id(), "CLAIM_DENIED", claimantId, claimDecision.reason());
            throw new IllegalStateException(claimDecision.reason());
        }
        final Instant now     = Instant.now();
        var           builder = item.toBuilder();
        if (item.lastReturnedToPoolAt() != null) {
            builder.accumulatedUnclaimedSeconds(item.accumulatedUnclaimedSeconds() + Duration.between(item.lastReturnedToPoolAt(), now).toSeconds());
            builder.lastReturnedToPoolAt(null);
        }
        builder.status(WorkItemStatus.ASSIGNED).assigneeId(claimantId).assignedAt(now);
        final io.casehub.work.api.WorkItem saved = workItemStore.put(builder.build());
        timerService.cancelClaimDeadline(saved.id());
        audit(saved.id(), "ASSIGNED", claimantId, null);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("ASSIGNED", saved, claimantId, null));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem start(final UUID id, final String actorId) {
        final io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status() != WorkItemStatus.ASSIGNED) {
            throw new IllegalStateException("Cannot start WorkItem in status: " + item.status());
        }
        final io.casehub.work.api.WorkItem updated = item.toBuilder()
                                                         .status(WorkItemStatus.IN_PROGRESS)
                                                         .startedAt(Instant.now())
                                                         .build();
        final io.casehub.work.api.WorkItem saved = workItemStore.put(updated);
        audit(saved.id(), "STARTED", actorId, null);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("STARTED", saved, actorId, null));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem completeFromSystem(final UUID id, final String actorId, final String resolution) {
        final io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status().isTerminal()) {return item;}
        final io.casehub.work.api.WorkItem updated = item.toBuilder()
                                                         .status(WorkItemStatus.COMPLETED)
                                                         .completedAt(Instant.now())
                                                         .resolution(resolution)
                                                         .build();
        final io.casehub.work.api.WorkItem saved = workItemStore.put(updated);
        timerService.cancelExpiry(saved.id());
        timerService.cancelClaimDeadline(saved.id());
        audit(saved.id(), "COMPLETED", actorId, null);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("COMPLETED", saved, actorId, resolution));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem rejectFromSystem(final UUID id, final String actorId, final String reason) {
        final io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status().isTerminal()) {return item;}
        final io.casehub.work.api.WorkItem updated = item.toBuilder()
                                                         .status(WorkItemStatus.REJECTED)
                                                         .completedAt(Instant.now())
                                                         .build();
        final io.casehub.work.api.WorkItem saved = workItemStore.put(updated);
        timerService.cancelExpiry(saved.id());
        timerService.cancelClaimDeadline(saved.id());
        audit(saved.id(), "REJECTED", actorId, reason);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("REJECTED", saved, actorId, reason));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem complete(final UUID id, final String actorId, final String resolution,
                                                 final String outcome) {
        final io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status() != WorkItemStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot complete WorkItem in status: " + item.status());
        }
        outcomeValidator.validate(item, outcome, resolution, null, actorId);
        if (item.outputDataSchema() != null) {
            final List<String> violations = schemaValidator.validate(item.outputDataSchema(), resolution);
            if (!violations.isEmpty()) {
                throw new IllegalArgumentException("resolution violates outputDataSchema: " + violations);
            }
        }
        final io.casehub.work.api.WorkItem updated = item.toBuilder()
                                                         .status(WorkItemStatus.COMPLETED)
                                                         .completedAt(Instant.now())
                                                         .resolution(resolution)
                                                         .outcome(outcome)
                                                         .build();
        final io.casehub.work.api.WorkItem saved = workItemStore.put(updated);
        timerService.cancelExpiry(saved.id());
        timerService.cancelClaimDeadline(saved.id());
        audit(saved.id(), "COMPLETED", actorId, null);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("COMPLETED", saved, actorId, resolution));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem reject(final UUID id, final String actorId, final String reason, final String outcome) {
        final io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status() != WorkItemStatus.ASSIGNED && item.status() != WorkItemStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot reject WorkItem in status: " + item.status());
        }
        outcomeValidator.validate(item, outcome, null, reason, actorId);
        final io.casehub.work.api.WorkItem updated = item.toBuilder()
                                                         .status(WorkItemStatus.REJECTED)
                                                         .completedAt(Instant.now())
                                                         .outcome(outcome)
                                                         .build();
        final io.casehub.work.api.WorkItem saved = workItemStore.put(updated);
        timerService.cancelExpiry(saved.id());
        timerService.cancelClaimDeadline(saved.id());
        audit(saved.id(), "REJECTED", actorId, reason);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("REJECTED", saved, actorId, reason));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem complete(final UUID id, final String actorId, final String resolution,
                                                 final String outcome, final String rationale, final String planRef) {
        final io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status() != WorkItemStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot complete WorkItem in status: " + item.status());
        }
        outcomeValidator.validate(item, outcome, resolution, null, actorId);
        if (item.outputDataSchema() != null) {
            final List<String> violations = schemaValidator.validate(item.outputDataSchema(), resolution);
            if (!violations.isEmpty()) {
                throw new IllegalArgumentException("resolution violates outputDataSchema: " + violations);
            }
        }
        final io.casehub.work.api.WorkItem updated = item.toBuilder()
                                                         .status(WorkItemStatus.COMPLETED)
                                                         .completedAt(Instant.now())
                                                         .resolution(resolution)
                                                         .outcome(outcome)
                                                         .build();
        final io.casehub.work.api.WorkItem saved = workItemStore.put(updated);
        timerService.cancelExpiry(saved.id());
        timerService.cancelClaimDeadline(saved.id());
        audit(saved.id(), "COMPLETED", actorId, null);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of(
                "COMPLETED", saved, actorId, resolution, rationale, planRef));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem reject(final UUID id, final String actorId, final String reason,
                                               final String outcome, final String rationale) {
        final io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status() != WorkItemStatus.ASSIGNED && item.status() != WorkItemStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot reject WorkItem in status: " + item.status());
        }
        outcomeValidator.validate(item, outcome, null, reason, actorId);
        final io.casehub.work.api.WorkItem updated = item.toBuilder()
                                                         .status(WorkItemStatus.REJECTED)
                                                         .completedAt(Instant.now())
                                                         .outcome(outcome)
                                                         .build();
        final io.casehub.work.api.WorkItem saved = workItemStore.put(updated);
        timerService.cancelExpiry(saved.id());
        timerService.cancelClaimDeadline(saved.id());
        audit(saved.id(), "REJECTED", actorId, reason);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of(
                "REJECTED", saved, actorId, reason, rationale, null));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem delegate(final UUID id, final String actorId, final String toAssigneeId,
                                                 final DeclineTarget declineTarget) {
        io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status() != WorkItemStatus.ASSIGNED && item.status() != WorkItemStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot delegate WorkItem in status: " + item.status());
        }
        final PolicyDecision delegateDecision = exclusionPolicy.check(toAssigneeId, item.excludedUsers());
        if (delegateDecision.denied()) {
            blockedAuditService.record(item.id(), "DELEGATE_DENIED", actorId,
                                       "target:" + toAssigneeId + "; reason:" + delegateDecision.reason());
            throw new IllegalArgumentException(delegateDecision.reason());
        }
        var builder = item.toBuilder();
        if (item.owner() == null) {
            builder.owner(actorId);
        }
        builder.delegationChain(item.delegationChain() == null
                                ? actorId
                                : item.delegationChain() + "," + actorId);
        item    = builder.build();
        item    = assignmentService.assign(item, AssignmentTrigger.DELEGATED);
        builder = item.toBuilder();
        if (item.assigneeId() == null || item.assigneeId().equals(actorId)) {
            builder.assigneeId(toAssigneeId);
        }
        builder.status(WorkItemStatus.DELEGATED)
               .claimDeadline(null)
               .lastReturnedToPoolAt(null)
               .delegationDeclineTarget(declineTarget);
        final io.casehub.work.api.WorkItem saved = workItemStore.put(builder.build());
        timerService.cancelClaimDeadline(saved.id());
        audit(saved.id(), "DELEGATED", actorId, "to:" + saved.assigneeId());
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("DELEGATED", saved, actorId, "to:" + toAssigneeId));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem acceptDelegation(final UUID id, final String claimantId) {
        if (claimantId == null || claimantId.isBlank()) {
            throw new IllegalArgumentException("claimantId is required");
        }
        final io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status() != WorkItemStatus.DELEGATED) {
            throw new IllegalStateException(
                    "Cannot accept delegation for WorkItem in status: " + item.status());
        }
        if (!claimantId.equals(item.assigneeId())) {
            throw new IllegalStateException(
                    "Actor '" + claimantId + "' is not the designated delegatee for WorkItem " + id);
        }
        final io.casehub.work.api.WorkItem updated = item.toBuilder()
                                                         .status(WorkItemStatus.ASSIGNED)
                                                         .assignedAt(Instant.now())
                                                         .delegationDeclineTarget(null)
                                                         .build();
        final io.casehub.work.api.WorkItem saved = workItemStore.put(updated);
        audit(saved.id(), "DELEGATION_ACCEPTED", claimantId, null);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("DELEGATION_ACCEPTED", saved, claimantId, null));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem declineDelegation(final UUID id, final String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId is required");
        }
        io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status() != WorkItemStatus.DELEGATED) {
            throw new IllegalStateException(
                    "Cannot decline delegation for WorkItem in status: " + item.status());
        }
        if (!actorId.equals(item.assigneeId())) {
            throw new IllegalStateException(
                    "Actor '" + actorId + "' is not the designated delegatee for WorkItem " + id);
        }
        final DeclineTarget target  = resolveDeclineTarget(item);
        var                 builder = item.toBuilder().delegationDeclineTarget(null);

        if (target == DeclineTarget.DELEGATOR && item.delegationChain() != null) {
            final String[] chain     = item.delegationChain().split(",");
            final String   prevActor = chain[chain.length - 1].trim();
            builder.assigneeId(prevActor)
                   .status(WorkItemStatus.ASSIGNED)
                   .assignedAt(Instant.now());
        } else {
            builder.assigneeId(null).status(WorkItemStatus.PENDING);
            final Instant now = Instant.now();
            builder.lastReturnedToPoolAt(now);
            item    = builder.build();
            builder = item.toBuilder();
            builder.claimDeadline(claimSlaPolicy.computePoolDeadline(buildClaimSlaContext(item, now)));
            item    = builder.build();
            item    = assignmentService.assign(item, AssignmentTrigger.DELEGATION_DECLINED);
            builder = item.toBuilder();
        }

        final io.casehub.work.api.WorkItem saved = workItemStore.put(builder.build());
        if (saved.claimDeadline() != null) {
            timerService.scheduleClaimDeadline(saved.id(), saved.tenancyId(), saved.claimDeadline());
        }
        audit(saved.id(), "DELEGATION_DECLINED", actorId, null);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("DELEGATION_DECLINED", saved, actorId, null));
        return saved;
    }

    private DeclineTarget resolveDeclineTarget(final io.casehub.work.api.WorkItem item) {
        if (item.delegationDeclineTarget() != null) {
            return item.delegationDeclineTarget();
        }
        final Path        scopePath = item.scope() != null ? Path.parse(item.scope()) : Path.root();
        final Preferences prefs     = preferenceProvider.resolve(new SettingsScope(item.tenancyId(), scopePath, Instant.now()));
        return prefs.getOrDefault(DeclineTarget.KEY);
    }

    public Optional<io.casehub.work.api.WorkItem> findById(final UUID id) {
        return workItemStore.get(id);
    }

    public List<io.casehub.work.api.WorkItem> scan(final WorkItemQuery query) {
        return workItemStore.scan(query);
    }

    @Transactional
    public io.casehub.work.api.WorkItem release(final UUID id, final String actorId) {
        io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status() != WorkItemStatus.ASSIGNED) {
            throw new IllegalStateException("Cannot release WorkItem in status: " + item.status());
        }
        final Instant now = Instant.now();
        item = item.toBuilder()
                   .status(WorkItemStatus.PENDING)
                   .assigneeId(null)
                   .lastReturnedToPoolAt(now)
                   .build();
        item = item.toBuilder()
                   .claimDeadline(claimSlaPolicy.computePoolDeadline(buildClaimSlaContext(item, now)))
                   .build();
        item = assignmentService.assign(item, AssignmentTrigger.RELEASED);
        final io.casehub.work.api.WorkItem saved = workItemStore.put(item);
        if (saved.claimDeadline() != null) {
            timerService.scheduleClaimDeadline(saved.id(), saved.tenancyId(), saved.claimDeadline());
        }
        audit(saved.id(), "RELEASED", actorId, null);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("RELEASED", saved, actorId, null));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem suspend(final UUID id, final String actorId, final String reason) {
        final io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status() != WorkItemStatus.ASSIGNED && item.status() != WorkItemStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot suspend WorkItem in status: " + item.status());
        }
        final io.casehub.work.api.WorkItem updated = item.toBuilder()
                                                         .priorStatus(item.status())
                                                         .status(WorkItemStatus.SUSPENDED)
                                                         .suspendedAt(Instant.now())
                                                         .build();
        final io.casehub.work.api.WorkItem saved = workItemStore.put(updated);
        timerService.cancelExpiry(saved.id());
        timerService.cancelClaimDeadline(saved.id());
        audit(saved.id(), "SUSPENDED", actorId, reason);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("SUSPENDED", saved, actorId, reason));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem resume(final UUID id, final String actorId) {
        final io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status() != WorkItemStatus.SUSPENDED) {
            throw new IllegalStateException("Cannot resume WorkItem in status: " + item.status());
        }
        final io.casehub.work.api.WorkItem updated = item.toBuilder()
                                                         .status(item.priorStatus())
                                                         .priorStatus(null)
                                                         .suspendedAt(null)
                                                         .build();
        final io.casehub.work.api.WorkItem saved = workItemStore.put(updated);
        if (saved.expiresAt() != null) {
            timerService.scheduleExpiry(saved.id(), saved.tenancyId(), saved.expiresAt());
        }
        if (saved.claimDeadline() != null) {
            timerService.scheduleClaimDeadline(saved.id(), saved.tenancyId(), saved.claimDeadline());
        }
        audit(saved.id(), "RESUMED", actorId, null);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("RESUMED", saved, actorId, null));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem cancel(final UUID id, final String actorId, final String reason) {
        final io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status().isTerminal()) {
            throw new IllegalStateException("Cannot cancel WorkItem in status: " + item.status());
        }
        final io.casehub.work.api.WorkItem updated = item.toBuilder()
                                                         .status(WorkItemStatus.CANCELLED)
                                                         .completedAt(Instant.now())
                                                         .build();
        final io.casehub.work.api.WorkItem saved = workItemStore.put(updated);
        timerService.cancelExpiry(saved.id());
        timerService.cancelClaimDeadline(saved.id());
        audit(saved.id(), "CANCELLED", actorId, reason);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("CANCELLED", saved, actorId, reason));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem fault(final UUID id, final String systemActorId, final String errorDetail) {
        final io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status().isTerminal()) {
            throw new IllegalStateException("Cannot fault WorkItem in status: " + item.status());
        }
        final io.casehub.work.api.WorkItem updated = item.toBuilder()
                                                         .status(WorkItemStatus.FAULTED)
                                                         .completedAt(Instant.now())
                                                         .resolution(errorDetail)
                                                         .build();
        final io.casehub.work.api.WorkItem saved = workItemStore.put(updated);
        timerService.cancelExpiry(saved.id());
        timerService.cancelClaimDeadline(saved.id());
        audit(saved.id(), "FAULTED", systemActorId, errorDetail);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("FAULTED", saved, systemActorId, errorDetail));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem faultFromSystem(final UUID id, final String actorId, final String errorDetail) {
        final io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status().isTerminal()) {return item;}
        final io.casehub.work.api.WorkItem updated = item.toBuilder()
                                                         .status(WorkItemStatus.FAULTED)
                                                         .completedAt(Instant.now())
                                                         .resolution(errorDetail)
                                                         .build();
        final io.casehub.work.api.WorkItem saved = workItemStore.put(updated);
        timerService.cancelExpiry(saved.id());
        timerService.cancelClaimDeadline(saved.id());
        audit(saved.id(), "FAULTED", actorId, errorDetail);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("FAULTED", saved, actorId, errorDetail));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem obsolete(final UUID id, final String triggeredBy, final String reason) {
        final io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status().isTerminal()) {
            throw new IllegalStateException("Cannot obsolete WorkItem in status: " + item.status());
        }
        final io.casehub.work.api.WorkItem updated = item.toBuilder()
                                                         .status(WorkItemStatus.OBSOLETE)
                                                         .completedAt(Instant.now())
                                                         .resolution(reason)
                                                         .build();
        final io.casehub.work.api.WorkItem saved = workItemStore.put(updated);
        timerService.cancelExpiry(saved.id());
        timerService.cancelClaimDeadline(saved.id());
        audit(saved.id(), "OBSOLETE", triggeredBy, reason);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("OBSOLETE", saved, triggeredBy, reason));
        cascadeObsoleteToSpawnGroup(saved.id(), triggeredBy);
        return saved;
    }

    private void cascadeObsoleteToSpawnGroup(final UUID parentId, final String triggeredBy) {
        if (spawnGroupStore == null || relationStore == null) {return;}
        spawnGroupStore.findMultiInstanceByParentId(parentId).ifPresent(group -> {
            if (group.policyTriggered) {return;}
            group.policyTriggered = true;
            spawnGroupStore.put(group);

            relationStore.findByTargetAndType(parentId, WorkItemRelationType.PART_OF)
                         .forEach(rel -> workItemStore.get(rel.sourceId).ifPresent(child -> {
                             if (!child.status().isTerminal()) {
                                 final io.casehub.work.api.WorkItem cancelled = child.toBuilder()
                                                                                     .status(WorkItemStatus.CANCELLED)
                                                                                     .completedAt(Instant.now())
                                                                                     .resolution("Parent gate obsoleted")
                                                                                     .build();
                                 workItemStore.put(cancelled);
                                 timerService.cancelExpiry(child.id());
                                 timerService.cancelClaimDeadline(child.id());
                                 audit(child.id(), "CANCELLED", triggeredBy,
                                       "Cascade from parent gate obsolete");
                                 lifecycleEmitter.emit(WorkItemLifecycleEvent.of(
                                         "CANCELLED", cancelled, triggeredBy,
                                         "Cascade from parent gate obsolete"));
                             }
                         }));
        });
    }

    public List<io.casehub.work.api.WorkItem> findChildrenByParentId(final UUID parentId) {
        return workItemStore.findByParentId(parentId);
    }


    @Transactional
    public io.casehub.work.api.WorkItem obsoleteFromSystem(final UUID id, final String triggeredBy, final String reason) {
        final io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status().isTerminal()) {return item;}
        final io.casehub.work.api.WorkItem updated = item.toBuilder()
                                                         .status(WorkItemStatus.OBSOLETE)
                                                         .completedAt(Instant.now())
                                                         .resolution(reason)
                                                         .build();
        final io.casehub.work.api.WorkItem saved = workItemStore.put(updated);
        timerService.cancelExpiry(saved.id());
        timerService.cancelClaimDeadline(saved.id());
        audit(saved.id(), "OBSOLETE", triggeredBy, reason);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("OBSOLETE", saved, triggeredBy, reason));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem escalate(final UUID id, final String actor,
                                                 final String targetGroup, final String reason) {
        io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status().isTerminal()) {
            throw new IllegalStateException("Cannot escalate WorkItem in status: " + item.status());
        }
        final Instant now = Instant.now();
        item = item.toBuilder()
                   .candidateGroups(targetGroup)
                   .assigneeId(null)
                   .status(WorkItemStatus.PENDING)
                   .lastReturnedToPoolAt(now)
                   .build();
        item = item.toBuilder()
                   .claimDeadline(claimSlaPolicy.computePoolDeadline(buildClaimSlaContext(item, now)))
                   .build();
        item = assignmentService.assign(item, AssignmentTrigger.SLA_ESCALATED);
        final io.casehub.work.api.WorkItem saved = workItemStore.put(item);
        timerService.cancelClaimDeadline(saved.id());
        if (saved.claimDeadline() != null) {
            timerService.scheduleClaimDeadline(saved.id(), saved.tenancyId(), saved.claimDeadline());
        }
        if (saved.expiresAt() != null) {
            timerService.rescheduleExpiry(saved.id(), saved.expiresAt());
        }
        audit(saved.id(), "MANUALLY_ESCALATED", actor, reason);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("MANUALLY_ESCALATED", saved, actor, reason));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem cancelFromSystem(final UUID id, final String actorId, final String reason) {
        final io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status().isTerminal()) {return item;}
        final io.casehub.work.api.WorkItem updated = item.toBuilder()
                                                         .status(WorkItemStatus.CANCELLED)
                                                         .completedAt(Instant.now())
                                                         .build();
        final io.casehub.work.api.WorkItem saved = workItemStore.put(updated);
        timerService.cancelExpiry(saved.id());
        timerService.cancelClaimDeadline(saved.id());
        audit(saved.id(), "CANCELLED", actorId, reason);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("CANCELLED", saved, actorId, reason));
        return saved;
    }

    @jakarta.transaction.Transactional
    public io.casehub.work.api.WorkItem compensate(final UUID originalId,
                                                   final WorkItemCreateRequest request,
                                                   final String triggeredBy,
                                                   final String reason) {
        final io.casehub.work.api.WorkItem original = requireWorkItem(originalId);

        if (original.status() != WorkItemStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Only COMPLETED WorkItems can be compensated; current status: " + original.status());
        }
        if (original.compensationStatus() != null
            && original.compensationStatus() != CompensationStatus.NONE) {
            throw new IllegalStateException(
                    "WorkItem already has compensation activity: " + original.compensationStatus());
        }
        if (original.compensatesWorkItemId() != null) {
            throw new IllegalStateException(
                    "Compensating WorkItems cannot themselves be compensated");
        }

        final io.casehub.work.api.WorkItem updatedOriginal = original.toBuilder()
                                                                     .compensationStatus(CompensationStatus.COMPENSATING)
                                                                     .build();
        workItemStore.put(updatedOriginal);
        audit(originalId, "COMPENSATION_STARTED", triggeredBy, reason);

        final io.casehub.work.api.WorkItem compensating = create(request);

        final io.casehub.work.api.WorkItem linked = compensating.toBuilder()
                                                                .compensatesWorkItemId(originalId)
                                                                .build();
        final io.casehub.work.api.WorkItem saved = workItemStore.put(linked);

        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("COMPENSATION_STARTED",
                                                        updatedOriginal, triggeredBy, reason));

        return saved;
    }

    @jakarta.transaction.Transactional
    public io.casehub.work.api.WorkItem markCompensated(final UUID originalId) {
        final io.casehub.work.api.WorkItem original = requireWorkItem(originalId);
        final io.casehub.work.api.WorkItem updated = original.toBuilder()
                                                             .compensationStatus(CompensationStatus.COMPENSATED)
                                                             .build();
        final io.casehub.work.api.WorkItem saved = workItemStore.put(updated);
        audit(originalId, "COMPENSATION_COMPLETED", "system", "Compensating WorkItem completed");
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("COMPENSATION_COMPLETED",
                                                        saved, "system", null));
        return saved;
    }


    @Transactional
    public io.casehub.work.api.WorkItem extend(final UUID id, final Instant newExpiresAt, final String actorId) {
        if (newExpiresAt == null) {
            throw new IllegalArgumentException("newExpiresAt is required");
        }
        final io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status().isTerminal()) {
            throw new IllegalStateException("Cannot extend WorkItem in status: " + item.status());
        }
        if (item.expiresAt() != null && !newExpiresAt.isAfter(item.expiresAt())) {
            throw new IllegalArgumentException(
                    "newExpiresAt must be after current expiresAt (" + item.expiresAt() + ")");
        }
        final io.casehub.work.api.WorkItem updated = item.toBuilder()
                                                         .expiresAt(newExpiresAt)
                                                         .updatedAt(Instant.now())
                                                         .build();
        final io.casehub.work.api.WorkItem saved = workItemStore.put(updated);
        timerService.rescheduleExpiry(saved.id(), newExpiresAt);
        audit(saved.id(), "DEADLINE_EXTENDED", actorId, null);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("DEADLINE_EXTENDED", saved, actorId, null));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem updateDeadline(final UUID id, final Instant newDeadline, final String actorId) {
        if (newDeadline == null) {
            throw new IllegalArgumentException("newDeadline is required");
        }
        final io.casehub.work.api.WorkItem item = requireWorkItem(id);
        if (item.status().isTerminal()) {
            throw new IllegalStateException("Cannot update deadline on WorkItem in status: " + item.status());
        }
        final Instant oldDeadline = item.expiresAt();
        final io.casehub.work.api.WorkItem updated = item.toBuilder()
                                                         .expiresAt(newDeadline)
                                                         .updatedAt(Instant.now())
                                                         .build();
        final io.casehub.work.api.WorkItem saved = workItemStore.put(updated);
        timerService.rescheduleExpiry(saved.id(), newDeadline);
        audit(saved.id(), "DEADLINE_UPDATED", actorId,
              "old=" + oldDeadline + ", new=" + newDeadline);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("DEADLINE_UPDATED", saved, actorId, null));
        return saved;
    }


    @Transactional
    public io.casehub.work.api.WorkItem addLabel(final UUID workItemId, final String path, final String appliedBy) {
        final io.casehub.work.api.WorkItem item = workItemStore.get(workItemId)
                                                               .orElseThrow(() -> new WorkItemNotFoundException(workItemId));
        final java.util.List<io.casehub.work.api.WorkItemLabel> labels = new java.util.ArrayList<>(item.labels());
        labels.add(new io.casehub.work.api.WorkItemLabel(path, LabelPersistence.MANUAL, appliedBy));
        final io.casehub.work.api.WorkItem updated = item.toBuilder().labels(labels).build();
        final io.casehub.work.api.WorkItem saved   = workItemStore.put(updated);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("LABEL_ADDED", saved, appliedBy, null));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem removeLabel(final UUID workItemId, final String path) {
        final io.casehub.work.api.WorkItem item = workItemStore.get(workItemId)
                                                               .orElseThrow(() -> new WorkItemNotFoundException(workItemId));
        final java.util.List<io.casehub.work.api.WorkItemLabel> labels = new java.util.ArrayList<>(item.labels());
        final boolean removed = labels.removeIf(
                l -> l.path().equals(path) && l.persistence() == LabelPersistence.MANUAL);
        if (!removed) {
            throw new LabelNotFoundException(workItemId, path);
        }
        final io.casehub.work.api.WorkItem updated = item.toBuilder().labels(labels).build();
        final io.casehub.work.api.WorkItem saved   = workItemStore.put(updated);
        lifecycleEmitter.emit(WorkItemLifecycleEvent.of("LABEL_REMOVED", saved, "system", path));
        return saved;
    }

    @Transactional
    public io.casehub.work.api.WorkItem clone(final UUID sourceId, final String titleOverride, final String createdBy) {
        final io.casehub.work.api.WorkItem source = workItemStore.get(sourceId)
                                                                 .orElseThrow(() -> new WorkItemNotFoundException(sourceId));

        final String title = (titleOverride != null && !titleOverride.isBlank())
                             ? titleOverride
                             : source.title() + " (copy)";

        final java.util.List<io.casehub.work.api.WorkItemLabel> manualLabels = source.labels() == null
                                                                               ? java.util.List.of()
                                                                               : source.labels().stream()
                                                                                       .filter(l -> l.persistence() == LabelPersistence.MANUAL)
                                                                                       .toList();

        final WorkItemCreateRequest req = WorkItemCreateRequest.builder()
                                                               .title(title)
                                                               .description(source.description())
                                                               .types(source.types() != null ? java.util.List.copyOf(source.types()) : null)
                                                               .formKey(source.formKey())
                                                               .priority(source.priority())
                                                               .candidateGroups(source.candidateGroups())
                                                               .candidateUsers(source.candidateUsers())
                                                               .requiredCapabilities(source.requiredCapabilities())
                                                               .createdBy(createdBy)
                                                               .payload(source.payload())
                                                               .excludedUsers(source.excludedUsers())
                                                               .build();

        io.casehub.work.api.WorkItem cloned = create(req);

        for (final io.casehub.work.api.WorkItemLabel label : manualLabels) {
            cloned = addLabel(cloned.id(), label.path(), label.appliedBy());
        }

        return cloned;
    }

    public Optional<io.casehub.work.api.WorkItem> findByCallerRef(final String callerRef) {
        return workItemStore.findByCallerRef(callerRef);
    }

    public Optional<io.casehub.work.api.WorkItem> findActiveByCallerRef(final String callerRef) {
        return workItemStore.findActiveByCallerRef(callerRef);
    }

    private ClaimSlaContext buildClaimSlaContext(final io.casehub.work.api.WorkItem item, final Instant now) {
        final Duration totalPoolSla = config.defaultClaimHours() > 0
                                      ? Duration.ofHours(config.defaultClaimHours())
                                      : Duration.ofHours(24);
        final Duration accumulated = Duration.ofSeconds(item.accumulatedUnclaimedSeconds());
        final Instant  submitted   = item.createdAt() != null ? item.createdAt() : now;
        return new ClaimSlaContext(submitted, totalPoolSla, accumulated, now);
    }

    private io.casehub.work.api.WorkItem requireWorkItem(final UUID id) {
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

    /**
     * Resolve a business-hours count to an absolute {@link Instant} using the configured
     * {@link BusinessCalendar}. Falls back to wall-clock hours when no BusinessCalendar CDI bean
     * is available.
     */
    private Instant resolveBusinessHours(final Instant from, final int businessHours) {
        if (businessCalendar != null && !businessCalendar.isUnsatisfied()) {
            final java.time.ZoneId zone = java.time.ZoneId.of(config.businessHours().timezone());
            return businessCalendar.get().addBusinessDuration(from, Duration.ofHours(businessHours), zone);
        }
        // Fallback: treat as wall-clock hours when no calendar configured
        return from.plus(businessHours, ChronoUnit.HOURS);
    }
}
