# casehub-work — Contributor Guide

> Internals, architecture, and extension points for platform builders working on casehub-work itself.

**GitHub:** [casehubio/work](https://github.com/casehubio/work)

---

## Module Architecture

30 modules across 5 functional areas:

### Core Domain

| Module | Artifact | Type | Purpose |
|--------|----------|------|---------|
| `api/` | `casehub-work-api` | Pure-Java SPI | 17 SPI interfaces in `io.casehub.work.api.spi`, event types (`WorkEventType` — 26 values), `WorkItemRef`, `WorkItemCreateRequest` (builder), `WorkItemStatusEvent`, `BreachDecision` (sealed), `SelectionContext`, `MultiInstanceConfig`, `GroupStatus`, `Outcome`, `Capability`, `WorkCloudEventTypes`, `NormativeResolution`, `WorkItemPriority`, `DeclineTarget`, `LabelPersistence`, `ValidationMode`. Depends on `casehub-platform-api`. |
| `core/` | `casehub-work-core` | Jandex library | `WorkBroker` + 3 selection strategies (`LeastLoadedStrategy`, `ClaimFirstStrategy`, `RoundRobinStrategy`), 4 claim SLA policies (`ContinuationPolicy`, `FreshClockPolicy`, `SingleBudgetPolicy`, `PhaseClockPolicy`), `CapabilityValidator`, `WorkCapabilitiesRegistry`, `RoutingCursorStore`, `NoOpWorkerRegistry`, `NoOpRoutingCursorStore`, `PermissiveCapabilityRegistry`. No JPA, no Quarkus extension. Engine depends on this — gets worker routing without WorkItem entities or datasource. |
| `runtime/` | `casehub-work` | Quarkus extension | WorkItem JPA entity (40+ fields), `WorkItemService`, `WorkItemTemplateService`, `WorkItemAssignmentService`, `WorkItemSpawnService`, `WorkItemScheduleService`, `WorkItemTimerService`, `ExpiryLifecycleService`, `FormSchemaValidationService`, `LabelVocabularyService`, `OutcomeValidator`, `TemplateExpander`, `CapabilityParser`, CDI event emission (`WorkItemLifecycleEmitter`, `WorkItemGroupLifecycleEmitter`), `WorkCloudEventAdapter`/`WorkCloudEventInboundAdapter`, `LabelRuleEngine` + `LabelRuleEntity` (platform LabelRule migration), `WorkPreferenceRegistrar`, `WorkRlsPolicyApplicator`, timer jobs (`ExpiryTimerJob`, `ClaimDeadlineTimerJob`, `RoutingCursorCleanupJob`), business calendar (`DefaultBusinessCalendar`, `ConfigHolidayCalendar`, `ICalHolidayCalendar`), multi-instance coordinators, 18+ JPA repository implementations. |
| `deployment/` | `casehub-work-deployment` | Extension deployment | Build-time `@BuildStep` processor for the Quarkus extension. |

### REST

| Module | Artifact | Type | Purpose |
|--------|----------|------|---------|
| `rest/` | `casehub-work-rest` | Jandex library | `WorkItemResource`, `WorkItemTemplateResource`, `WorkItemBulkResource`, `WorkItemInstancesResource`, `WorkItemSpawnResource`, `SpawnGroupResource`, `WorkItemRelationResource`, `WorkItemScheduleResource`, `AuditResource`, `LabelRuleResource`, `VocabularyResource`, `AsyncApiResource`. DTOs: `CreateWorkItemRequest`, `CompleteRequest`, `CancelRequest`, `DelegateRequest`, `EscalateRequest`, `ExtendRequest`, `FaultRequest`, `ObsoleteRequest`, `RejectRequest`, `SuspendRequest`, `WorkItemResponse`, `WorkItemWithAuditResponse`, `WorkItemLabelResponse`. Mappers: `WorkItemMapper`. Exception mappers for `IllegalStateException`, `OptimisticLockException`, `MalformedCapabilityException`, `UnknownCapabilityException`, `WorkItemNotFoundException`. |

### Optional Extensions

| Module | Artifact | Type | Purpose |
|--------|----------|------|---------|
| `engine-adapter/` | `casehub-work-engine-adapter` | Bridge | Two-way bridge between engine PlanItems and work WorkItems. Contains: `HumanTaskScheduleHandler` (outbound: engine -> work), `WorkItemLifecycleAdapter` (inbound: work -> engine), `PlanItemCompletionApplier` (transition routing), `ActionGateWorkItemHandler` + `ActionGateCompletionApplier` + `ActionGateCancelledHandler` (oversight gate bridge), `InboundWorkItemSchedulerImpl` (inbound qhorus messages -> WorkItems via TenantContextExecutor), `WorkActorStateContributor` (actor state view — active WorkItems by assignee), `WorkStrategyContributor` (NamedStrategy registration), `HumanTaskRecoveryService` (startup recovery), `JpaPlanItemStore`, `WorkAdapterPlanItemEntity`, `CallerRef` sealed interface with `PlanItemRef` + `GateRef` variants. Lives here (not in engine) because the bridge owns the WorkItem entity and transaction boundaries. |
| `flow/` | `casehub-work-flow` | Jandex library | `WorkItemsFlow` (extends Quarkus-Flow's `Flow`), `HumanTaskFlowBridge` (CDI bridge creating WorkItems and returning `Uni<String>` that suspends the workflow), `WorkItemTaskBuilder` (fluent DSL: `.title()`, `.description()`, `.assigneeId()`, `.candidateGroups()`, `.priority()`, `.payloadFrom()`, `.buildTask()`), `PendingWorkItemRegistry` (in-memory CompletableFuture registry), `WorkItemFlowEventListener` (lifecycle observer that resolves pending futures), `WorkItemResolutionException`. |
| `ledger/` | `casehub-work-ledger` | Optional | `LedgerEventCapture` (observes lifecycle events, writes ledger entries), `WorkItemLedgerEntry` entity, `WorkItemLedgerEntryRepository` SPI + `JpaWorkItemLedgerEntryRepository`, REST: `LedgerResource` (queries, provenance, attestation), `ActorTrustResource` (trust scores), DTOs for attestation and provenance. |
| `queues/` | `casehub-work-queues` | Optional | `QueueMembershipService`, `FilterEvaluationObserver`, `QueueSnapshotJob` (scheduled trend data collection), `WorkItemQueueEventBroadcaster` SPI + `LocalWorkItemQueueEventBroadcaster`, `WorkItemQueueMetrics`, `QueueResource` + `QueueStateResource` (REST), `WorkItemQueueState` + `QueueSnapshot` entities, `QueueSnapshotStore` + `QueueStateStore` + `WorkItemViewQuery` repositories, `QueueSnapshotInterval` + `QueueTrendRetention` config, `QueuesRlsPolicyApplicator`. |
| `queues-dashboard/` | `casehub-work-queues-dashboard` | Optional | `QueueDashboard` (SSE-driven TUI), `QueueBoardBuilder`, `DashboardMain`, `ReviewStepService`, `SecurityWritersFilter`. |
| `queues-postgres-broadcaster/` | — | Optional | `PostgresWorkItemQueueEventBroadcaster` — distributed SSE via PostgreSQL LISTEN/NOTIFY for queue events. |
| `ai/` | `casehub-work-ai` | Optional | `SemanticWorkerSelectionStrategy` (embedding-based routing), `EmbeddingSkillMatcher`, `CapabilitiesSkillProfileProvider`, `CompositeSkillProfileProvider`, `ResolutionHistorySkillProfileProvider`, `WorkerProfileSkillProfileProvider`, `WorkerSkillProfile` entity, `EscalationSummaryObserver` + `EscalationSummaryService` + `EscalationSummary` entity, `ResolutionSuggestionService` + `ResolutionSuggestionResource`, `LowConfidenceFilterProducer` (queue filter for AI confidence), REST: `WorkerSkillProfileResource`, `EscalationSummaryResource`, `ResolutionSuggestionResource`, `AiRlsPolicyApplicator`. |
| ~~`notifications/`~~ | ~~`casehub-work-notifications`~~ | **Removed** | Replaced by platform subscription engine (#315). `WorkItemSubscriptionBridge` in `runtime/` inserts lifecycle events into the platform DataSource. `WorkItemLifecycleEvent` implements `SubscribableEvent`. |
| `reports/` | `casehub-work-reports` | Optional | `ReportService`, `SlaBreachReport` + `SlaBreachItem`, `SlaSummary`, `ThroughputReport` + `ThroughputBucket` + `ThroughputBucketAggregator`, `QueueHealthReport`, `ActorReport`, REST: `ReportResource`. |
| `issue-tracker/` | `casehub-work-issue-tracker` | Optional | `IssueLinkService`, `IssueTrackerProvider` SPI + `GitHubIssueTrackerProvider`, `JiraIssueTrackerConfig`/`GitHubIssueTrackerConfig`, `WorkItemIssueLink` entity + `IssueLinkStore`, webhook handling: `WebhookEventHandler` + `WebhookEvent` + `WebhookEventKind`, `GitHubWebhookResource` + `GitHubWebhookParser`, `JiraWebhookResource` + `JiraWebhookParser`, `ExternalIssueRef`, `NormativeResolution` (speech-act-based: DONE/DECLINE/FAILURE), `IssueTrackerRlsPolicyApplicator`. |
| `postgres-broadcaster/` | — | Optional | `PostgresWorkItemEventBroadcaster` + `WorkItemEventPayload` — distributed SSE for WorkItem lifecycle events via PostgreSQL LISTEN/NOTIFY. |

### Progress Model (6 modules)

| Module | Artifact | Type | Purpose |
|--------|----------|------|---------|
| `progress-api/` | `casehub-work-progress-api` | Pure-Java SPI | `ProgressInstance` (record), `ProgressStatus` (PENDING/ACTIVE/COMPLETED/FAILED), `StepDefinition` (record with name, optional flag, dependsOn, condition), `ProgressCreateRequest`, `ProgressUpdatedEvent`, `ProgressChangeType` (7 values), `RollupStrategy` SPI (extends NamedStrategy), `RollupContext`, `ConditionEvaluator` (functional), store SPIs: `ProgressInstanceStore`, `ProgressEventStore`. |
| `progress-core/` | `casehub-work-progress-core` | Jandex library | Rollup strategies: `AveragePercentageStrategy`, `CountCompletedStrategy`, `WeightedPercentageStrategy`, `RollupEngine`. Validators: `ShapeValidator`, `PercentageValidator`, `CountValidator`, `StepValidator`, `StepShapeValidator`, `StepDefinitionValidator`, `RollbackDetector`. |
| `progress-runtime/` | `casehub-work-progress-runtime` | Quarkus extension | `ProgressService` (create, updateState, complete, fail, reactivate, attachChild, step operations), `ProgressInstanceEntity` + `ProgressEventEntity` JPA entities, `JpaProgressInstanceStore` + `JpaProgressEventStore` repositories, `ProgressInstanceMapper`, `ProgressEventBroadcaster` SPI + `LocalProgressEventBroadcaster`, `RollupObserver` (CDI event observer triggering rollup on child changes). |
| `progress-deployment/` | `casehub-work-progress-deployment` | Extension deployment | `ProgressProcessor` — build-time `@BuildStep` for the progress Quarkus extension. |
| `progress-rest/` | `casehub-work-progress-rest` | Jandex library | `ProgressResource` (full REST surface: create, state update, complete, fail, reactivate, attach children, tree queries, event history, SSE streaming, step lifecycle: start/complete/skip/fail, step state update), DTOs: `CreateProgressRequest`, `UpdateStateRequest`, `UpdateStepDataRequest`. |
| `progress-memory/` | `casehub-work-progress-memory` | Test | `InMemoryProgressInstanceStore`, `InMemoryProgressEventStore` — ConcurrentHashMap-backed for test isolation. |

### Testing and Examples

| Module | Artifact | Type | Purpose |
|--------|----------|------|---------|
| `persistence-memory/` | `casehub-work-persistence-memory` | Test | In-memory stores for all runtime repository interfaces, `@Alternative @Priority(100)`. ConcurrentHashMap-backed (thread-safe). |
| `examples/` | — | Runnable | Demo scenarios for standalone WorkItem usage. |
| `queues-examples/` | — | Runnable | Queue pattern demos. |
| `flow-examples/` | — | Runnable | `WorkItemsFlow` DSL workflow demos. |
| `integration-tests/` | — | Test | `@QuarkusIntegrationTest` + native image testing. |
| `integration-tests-memory/` | — | Test | Boot verification through in-memory stores (no datasource). |

## Core/Runtime Split

`casehub-work-core` is a Jandex library (not a Quarkus extension) containing `WorkBroker`, selection strategies, claim SLA policies, and capability validation. Engine depends on this — gets worker routing without WorkItem entities, Flyway, or datasource requirements. REST is a separate opt-in module.

## Engine Adapter Internals

The two-way bridge between engine PlanItems and work WorkItems was relocated from engine (#290). It lives in casehub-work because the bridge owns the WorkItem entity and transaction boundaries.

**Outbound (engine -> work):** `HumanTaskScheduleHandler` receives `HumanTaskScheduleEvent` from the engine and creates a WorkItem via `WorkItemCreator.create()`. Uses `CallerRef` sealed interface with two variants: `PlanItemRef` (standard human tasks) and `GateRef` (oversight gates). Threads `candidateScores` and `routingExperiences` from the engine's evaluation context.

**Inbound (work -> engine):** `WorkItemLifecycleAdapter` observes `WorkItemLifecycleEvent` CDI events and routes terminal transitions back to the engine via `PlanItemCompletionApplier`. Threads `ledgerEntryId` (set by `LedgerEventCapture` on the event via `LedgerEntryIdSetter` SPI) through to both `PlanItemCompletionApplier` and `ActionGateCompletionApplier` for cross-ledger causal linking. Handles resolution data, outcome, rationale, and planRef fields. For multi-approver gates, aggregates `approvedBy` from child WorkItems via `WorkItemCreator.findChildApprovers()`.

**Action gates:** `ActionGateWorkItemHandler` creates WorkItems for oversight gates (human approval before automated actions). `ActionGateCompletionApplier` routes gate completions. `ActionGateCancelledHandler` handles gate cancellation.

**Recovery:** `HumanTaskRecoveryService` runs at startup to reconcile WorkItems with engine state after crashes.

**Strategy registration:** `WorkStrategyContributor` contributes work-module NamedStrategy beans to the engine's `EngineStrategyResolver`.

**CallerRef format:** `PlanItemRef` encodes `case:{caseId}/pi:{planItemId}`. `GateRef` encodes `case:{caseId}/gate:{gateId}`. Both parse via the `CallerRef` sealed interface, which extends `CrossSystemRef` (work-api) with `system()="engine"`. The qhorus module's `QhorusRef` similarly implements `CrossSystemRef` with `system()="qhorus"`.

**Planning module dependency:** The adapter was migrated from `casehub-engine-blackboard` to `casehub-engine-planning` (#322).

## Runtime Repository Architecture

The runtime defines 18+ store interfaces with JPA implementations:

| Store | Entity | Tenant-Scoped? |
|-------|--------|---------------|
| `WorkItemStore` | `WorkItem` (api/ record) | yes |
| `WorkItemTemplateStore` | `WorkItemTemplate` | yes |
| `AuditEntryStore` | `AuditEntry` | yes |
| `WorkItemScheduleStore` | `WorkItemSchedule` | yes |
| `WorkItemSpawnGroupStore` | `WorkItemSpawnGroup` | yes |
| `WorkItemLinkStore` | `WorkItemLink` | yes |
| `WorkItemNoteStore` | `WorkItemNote` | yes |
| `WorkItemRelationStore` | `WorkItemRelation` | yes |
| `LabelDefinitionStore` | `LabelDefinition` | yes |
| `LabelVocabularyStore` | `LabelVocabulary` | yes |
| `LabelRuleStore` | `LabelRuleEntity` | yes |
| `RoutingCursorStore` | `RoutingCursor` | cross-tenant |
| `CrossTenantWorkItemStore` | `WorkItem` (api/ record) | cross-tenant |
| `CrossTenantWorkItemScheduleStore` | `WorkItemSchedule` | cross-tenant |
| `CrossTenantRoutingCursorStore` | `RoutingCursor` | cross-tenant |

All tenant-scoped stores extend `TenantAwareStore` (JPA base). The `@CrossTenant` qualifier marks stores that bypass tenant filtering. Row-level security is applied via `WorkRlsPolicyApplicator`.

## Event Broadcasting Architecture

WorkItem lifecycle events flow through a two-layer broadcast system:

1. **Local:** `LocalWorkItemEventBroadcaster` (in-JVM SSE via `WorkItemEventBroadcaster` interface)
2. **Distributed:** `PostgresWorkItemEventBroadcaster` (optional — PostgreSQL LISTEN/NOTIFY for multi-node clusters)

Queue events have the same pattern: `LocalWorkItemQueueEventBroadcaster` + optional `PostgresWorkItemQueueEventBroadcaster`.

Progress events: `LocalProgressEventBroadcaster` (via `ProgressEventBroadcaster` interface). The `RollupObserver` listens for progress change events and triggers parent rollup computation.

## Filter Engine — LabelRule Migration

The filter engine was migrated from a custom `FilterScope` enum to platform `LabelRule` (#314). `LabelRuleEngine` evaluates platform `LabelRule` expressions against WorkItem context. `LabelRuleEntity` is the JPA entity with `V5004__label_rule_schema.sql`. The queues module was further migrated to the platform-view subject view toolkit (#312).

## Multi-Instance Coordination

`MultiInstanceCoordinator` in runtime orchestrates M-of-N group creation and completion. Assignment strategies are resolved by name:

| Strategy | ID | Behavior |
|----------|-----|---------|
| `PoolAssignmentStrategy` | `pool` | Copies parent candidates to all children (default) |
| `RoundRobinAssignmentStrategy` | `round-robin` | Distributes across workers |
| `ExplicitListAssignmentStrategy` | `explicit` | Assigns from explicit list |
| `CompositeInstanceAssignmentStrategy` | `composite` | Chains multiple strategies |

`MultiInstanceSpawnService` handles the actual creation. `MultiInstanceGroupPolicy` enforces threshold logic. `OnThresholdReached` controls what happens to remaining children (KEEP or CANCEL_REMAINING).

## Preference Integration

`WorkPreferenceRegistrar` (#197) registers work preference schemas at startup via the platform `PreferenceProvider`. Preference keys are declared in `WorkPreferenceKeys`:
- `casehub.work/sla.default-hours` (default: 24)
- `casehub.work/sla.default-claim-hours` (default: 4)
- `casehub.work/sla.on-completion-expiry` (per-tenant SLA breach action override, #375)
- `casehub.work/sla.on-claim-expiry` (per-tenant SLA breach action override, #375)
- `casehub.work/sla.extension-hours` (per-tenant extension hours override, #375)
- `casehub.work/sla.claim-extension-hours` (per-tenant claim extension hours override, #375)

These enable per-scope override of SLA defaults via the platform preference hierarchy. The breach action keys (#375) use colon-delimited syntax matching config properties (`fail`, `extend:PT6H`, `escalateTo:group:PT4H`, `exhausted:reason`). `PreferenceSlaBreachPolicyDecorator` (`@Priority APPLICATION+200`) checks these before delegating to the config-selected policy.

## Business Calendar

Three `HolidayCalendar` implementations:
- `ConfigHolidayCalendar` — static dates from config
- `ICalHolidayCalendar` — feeds from `.ics` URLs
- `HolidayCalendarProducer` — CDI producer selecting active implementation

`DefaultBusinessCalendar` combines working hours, days, and holidays for deadline calculation. Used by `WorkItemTimerService` when `claimDeadlineBusinessHours` or `expiresAtBusinessHours` are set on creation.

## Dependencies

**Depends on:** `casehub-platform-api` (Path, Preferences, ActorType, NamedStrategy, LabelRule, PreferenceProvider). Zero other casehubio deps in core.

**Depended on by:**
- `casehub-engine` — `casehub-work-api` for `WorkItemCreator`, `WorkItemLifecycle`, signal routing. Engine-adapter bridge lives here.
- `casehub-clinical` — Layer 2 adverse event WorkItems with GCP SLA
- `casehub-aml`, `casehub-life`, `casehub-devtown` — WorkItem inbox + SLA

## Notification Concern

**Resolved (#315).** The `casehub-work-notifications` module has been removed. Lifecycle event notifications now flow through the platform subscription engine via `WorkItemSubscriptionBridge` (in `runtime/`). `WorkItemLifecycleEvent` implements `SubscribableEvent` for type/tenant discrimination. The bridge is optional — active only when `casehub-platform` subscriptions module is on the classpath (`Instance<DataSourceRegistry>` unsatisfied = no-op).

## Recent Changes (since April 2026)

- **Progress model** (#237) — 6-module progress subsystem for platform-level observation and reporting
- **Routing context** (#756) — `candidateScores` and `routingExperiences` threaded through WorkItem creation and lifecycle for learning-based selection
- **Preference registration** (#197) — SLA preferences registered at startup via platform `PreferenceProvider`
- **LabelRule migration** (#314) — Filter engine migrated from custom `FilterScope` to platform `LabelRule`
- **Subject view migration** (#312) — Queues migrated to platform-view subject view toolkit
- **Engine adapter relocation** (#290) — HumanTask adapter bridge moved from engine to work
- **NamedStrategy retrofit** (#287) — 4 SPIs (`WorkerSelectionStrategy`, `SlaBreachPolicy`, `ClaimSlaPolicy`, `InstanceAssignmentStrategy`) now extend `NamedStrategy`
- **REST extraction** (#292) — REST endpoints extracted into standalone `casehub-work-rest` module
- **Types migration** (#291) — `types: Set<Path>` added to WorkItem and WorkItemTemplate (replaces legacy `category`)
- **Multi-instance gate creation** (#810) — `WorkItemCreator.createMultiInstance()` for programmatic M-of-N group creation from engine gates
- **Multi-approver aggregation** (#815, #816) — `resolutionTypeName` and `approvedBy` aggregation for multi-approver gates
- **Obsolete cascading** (#818) — `OBSOLETE` cascades to spawn group children
- **Virtual threads** (#319) — Engine adapter migrated from reactive to blocking + virtual threads
- **Planning module migration** (#322) — Engine adapter dependency moved from blackboard to planning module
- **CloudEvent idempotency** (V40) — Idempotency index for CloudEvent deduplication

## Pending Work

- `casehub-work-qhorus` adapter — MCP tools for agent-driven approval flows
- ~~Notification migration (#315)~~ — **done**: platform subscription engine + `WorkItemSubscriptionBridge`
- Progress: visualisation modes (#309), rollback control (#308), arbitrary JSON schema shapes (#307)
- Queue summary: caching (#306), database-level aggregation (#305)
- CloudEvent bridge for cross-service HumanTask creation (#299)
- WorkItem event mesh (#97), federation (#95), distributed coordination (#92)

## Design Documents

- [ARC42STORIES.MD](https://raw.githubusercontent.com/casehubio/work/main/docs/ARC42STORIES.MD) — domain model, SPI contracts, status enumeration, service class structure
- [adr/INDEX.md](https://raw.githubusercontent.com/casehubio/work/main/adr/INDEX.md) — architectural decision records
