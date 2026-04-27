# Quarkus WorkItems — Design Document

## Overview

Quarkus WorkItems provides human-scale WorkItem lifecycle management for the Quarkus
ecosystem. Any Quarkus application adds `quarkus-work` as a dependency and gets a
human task inbox — WorkItems with expiry, delegation, escalation, priority, and audit
trail — usable standalone or via optional integrations with Quarkus-Flow, CaseHub,
and Qhorus.

Primary design specification: `docs/specs/2026-04-14-tarkus-design.md`

---

## Glossary

| Term | System | Meaning |
|---|---|---|
| `Task` | CNCF Serverless Workflow / Quarkus-Flow | Machine-executed workflow step — milliseconds, no assignee, no expiry |
| `Task` | CaseHub | CMMN case work unit — assigned to any worker (human or agent) via capabilities |
| `WorkItem` | Quarkus WorkItems | Human-resolved unit of work — minutes/days, has assignee, expiry, delegation, audit |

**Rule:** A `Task` is controlled by a machine. A `WorkItem` waits for a human.

---

## Component Structure

Maven multi-module layout following Quarkiverse conventions:

| Module | Artifact | Purpose |
|---|---|---|
| Parent | `quarkus-work-parent` | BOM, version management |
| API | `quarkus-work-api` | Pure Java SPI contracts — `WorkerCandidate`, `SelectionContext` (workItemId, title, description, category, requiredCapabilities, candidateUsers, candidateGroups), `AssignmentDecision`, `AssignmentTrigger`, `WorkerSelectionStrategy`, `WorkerRegistry`, `WorkEventType` (includes `SPAWNED`), `WorkLifecycleEvent`, `WorkloadProvider`, `EscalationPolicy`, `SkillProfile`, `SkillProfileProvider`, `SkillMatcher`. Spawn SPI: `SpawnPort`, `SpawnRequest`, `ChildSpec`, `SpawnResult`, `SpawnedChild`. groupId `io.quarkiverse.work`. Zero runtime dependencies. CaseHub and other systems depend on this without pulling in the WorkItems stack. |
| Core | `quarkus-work-core` | Generic work management implementations — `WorkBroker` (generic assignment orchestrator), `LeastLoadedStrategy`, `ClaimFirstStrategy`, `NoOpWorkerRegistry`, claim SLA policies. No JPA entities, no REST resources. CaseHub depends on this module directly. Jandex-indexed library, groupId `io.quarkiverse.work`. |
| Runtime | `quarkus-work` | Core — WorkItem model, storage SPI, JPA defaults, service, REST API, lifecycle engine, labels, vocabulary. Includes `WorkItemContextBuilder`, `JpaWorkloadProvider`, runtime actions (`ApplyLabelAction`, `OverrideCandidateGroupsAction`, `SetPriorityAction`), filter engine (`FilterAction` SPI, `FilterRegistryEngine`, `JexlConditionEvaluator`, `PermanentFilterRegistry`, `DynamicFilterRegistry`, `FilterRule`, `FilterRuleResource` — relocated from core in #133). Subprocess spawning: `WorkItemSpawnService` (implements `SpawnPort`), `WorkItemSpawnGroup` entity (idempotency tracking), `WorkItemSpawnResource` (`POST /workitems/{id}/spawn`, `GET/DELETE /workitems/{id}/spawn-groups`), `SpawnGroupResource` (`GET /spawn-groups/{id}`). |
| Deployment | `quarkus-work-deployment` | Build-time processor — feature registration, native config |
| Testing | `quarkus-work-testing` | `InMemoryWorkItemStore` + `InMemoryAuditEntryStore` — no datasource needed for unit tests |
| Flow | `quarkus-work-flow` | Quarkus-Flow integration — `WorkItemsFlow` DSL base class, `HumanTaskFlowBridge`, `WorkItemFlowEventListener` |
| Ledger | `quarkus-work-ledger` | Optional accountability module — command/event ledger, SHA-256 hash chain, peer attestation, EigenTrust reputation. Extends `io.quarkiverse.ledger:quarkus-ledger` (shared base library — see ADR-0001). Zero core impact when absent. |
| Queues | `quarkus-work-queues` | Optional label-based work queues — `WorkItemFilter` (JEXL/JQ/Lambda), `FilterChain` derivation graph with cascade delete, `QueueView` named label-pattern queries with `additionalConditions` JEXL filtering, queue pickup (`PUT /workitems/{id}/pickup`), soft assignment (`relinquishable` flag). **Queue lifecycle events**: `WorkItemQueueEvent` CDI events (ADDED/REMOVED/CHANGED) with DB-backed `QueueMembershipTracker` (V2001 migration) for restart-consistent state. See ADR-0002. Zero core impact when absent. |
| Queue Examples | `quarkus-work-queues-examples` | Real-world queue routing scenarios — support triage cascade, legal compliance, finance approval chain, security exec-escalation, document review pipeline. Run via `POST /queue-examples/{name}/run`. |
| Queue Dashboard | `quarkus-work-queues-dashboard` | Tamboui terminal UI dashboard running inside Quarkus via `@QuarkusMain`. Shows live 3×3 queue board (tiers × states), step-by-step scenario control, console log. Observes `WorkItemLifecycleEvent` directly — zero polling delay. |
| Examples | `quarkus-work-examples` | Runnable scenario demos — 4 `@QuarkusTest` scenarios covering every ledger/audit capability via `POST /examples/{name}/run` |
| Flow Examples | `quarkus-work-flow-examples` | WorkItemsFlow DSL showcase — contract review workflow mixing automated `function()` and human `workItem()` steps |
| Integration Tests | `integration-tests` | Black-box `@QuarkusIntegrationTest` suite and native image validation |
| *(future)* | `quarkus-work-casehub` | CaseHub `WorkerRegistry` adapter (blocked: CaseHub not ready) |
| *(future)* | `quarkus-work-qhorus` | Qhorus MCP tools (blocked: Qhorus not ready) |
| MongoDB | `quarkus-work-persistence-mongodb` | MongoDB-backed `WorkItemStore` + `AuditEntryStore`. `candidateGroups`/`candidateUsers` stored as arrays; `WorkItemQuery` → MongoDB `Document` filter; `$regex` for label patterns. 27 tests via Dev Services. |
| Notifications | `quarkus-work-notifications` | Optional outbound notification module. CDI `AFTER_SUCCESS` observer dispatches lifecycle events to configured channels (HTTP webhook, Slack, Teams). `WorkItemNotificationRule` entity stores per-channel rules (eventTypes filter, category filter, targetUrl, optional HMAC secret). REST CRUD at `/workitem-notification-rules`. `NotificationChannel` SPI in `quarkus-work-api` — custom channels implement this as `@ApplicationScoped` CDI beans. Flyway V3000. Zero core impact when absent. |
| AI | `quarkus-work-ai` | `LowConfidenceFilterProducer` — CDI-produced permanent filter applying `ai/low-confidence` label (INFERRED) when `confidenceScore < threshold`. Config: `quarkus.work.ai.confidence-threshold` (default 0.7), `quarkus.work.ai.low-confidence-filter.enabled` (default true). Semantic skill matching: `WorkerSkillProfile` entity (V14 Flyway migration) + REST API at `/worker-skill-profiles` (CRUD); `SemanticWorkerSelectionStrategy` (`@Alternative @Priority(1)` — auto-activates when module on classpath, embeds WorkItem title+description and worker skill narrative, scores candidates by cosine similarity); `EmbeddingSkillMatcher` (`dev.langchain4j` cosine similarity, `Instance<EmbeddingModel>` optional injection — scores -1.0 when no model); `WorkerProfileSkillProfileProvider` (default, DB-backed); `CapabilitiesSkillProfileProvider` (`@Alternative` — joins capability tags); `ResolutionHistorySkillProfileProvider` (`@Alternative` — aggregates completion history). |
| *(future)* | `quarkus-work-redis` | Redis-backed `WorkItemStore` |

---

## Technology Stack

| Concern | Choice | Notes |
|---|---|---|
| Runtime | Java 21 (on Java 26 JVM) | `maven.compiler.release=21` |
| Framework | Quarkus 3.32.2 | Inherits `quarkiverse-parent:21` |
| Persistence | Hibernate ORM + Panache (active record) | UUID PKs, `@PrePersist` timestamps |
| Schema migrations | Flyway | `V1__initial_schema.sql`; consuming app owns datasource config |
| Scheduler | `quarkus-scheduler` | Expiry cleanup job |
| JDBC (dev/test) | H2 (optional dep) | PostgreSQL for production |
| Native image | GraalVM 25 | Validated: 19 `@QuarkusIntegrationTest` tests, 0.084s startup |

---

## Domain Model

### WorkItem (`runtime/model/`)

| Field | Type | Notes |
|---|---|---|
| `id` | UUID PK | Set in `@PrePersist` |
| `title` | String | Human-readable task name |
| `description` | String | What the human needs to do |
| `category` | String | Classification: "finance", "legal", "security-review" |
| `formKey` | String | UI form reference — how frontends render this item |
| `status` | WorkItemStatus enum | See lifecycle below |
| `priority` | WorkItemPriority enum | LOW, NORMAL, HIGH, CRITICAL |
| `assigneeId` | String | Who currently has it (actual owner) |
| `owner` | String | Who is ultimately responsible; set on first delegation |
| `candidateGroups` | String | Comma-separated groups who can claim |
| `candidateUsers` | String | Comma-separated users individually invited to claim |
| `requiredCapabilities` | String | Comma-separated capability tags for routing |
| `createdBy` | String | System or agent that created it |
| `delegationState` | DelegationState enum | null \| PENDING \| RESOLVED |
| `delegationChain` | String | Comma-separated prior assignees (audit trail) |
| `payload` | TEXT | JSON context for the human |
| `resolution` | TEXT | JSON decision from the human |
| `claimDeadline` | Instant | Must be claimed by; null → use config default (0 = no deadline) |
| `expiresAt` | Instant | Must be completed by; null → use config default |
| `followUpDate` | Instant | Reminder date; surfaces in inbox, no escalation |
| `createdAt` / `updatedAt` | Instant | Managed by `@PrePersist` / `@PreUpdate` |
| `assignedAt` | Instant | When claimed/assigned |
| `startedAt` | Instant | When IN_PROGRESS began |
| `completedAt` | Instant | When terminal state reached |
| `suspendedAt` | Instant | When SUSPENDED |
| `priorStatus` | WorkItemStatus | Status before suspension; restored on resume |
| `labels` | `List<WorkItemLabel>` | 0..n labels; see Label Model below |
| `confidenceScore` | Double | Nullable. Set by AI agents (0.0–1.0). Null = no AI metadata. Used by filter-registry to gate routing. V13 migration. |
| `callerRef` | String | Nullable. Opaque routing key set at spawn time. Stored and echoed in every `WorkItemLifecycleEvent`; never interpreted by quarkus-work. CaseHub embeds its `planItemId` here to route child completion back to the right `PlanItem`. V17 migration. |

**WorkItemSpawnGroup (`runtime/model/`)** — tracks a batch of children spawned together:

| Field | Type | Notes |
|---|---|---|
| `id` | UUID PK | Set in `@PrePersist` |
| `parentId` | UUID | The parent WorkItem that owns this group |
| `idempotencyKey` | String | Unique per parent — a second spawn call with the same `(parentId, idempotencyKey)` returns this group without creating new children |
| `createdAt` | Instant | When the group was created |

No completion state — that belongs to the caller (CaseHub's `Stage.requiredItemIds`). V18 migration.

**Business-hours SLA fields** — V19 migration adds two columns to `work_item_template`:

| Field | Location | Notes |
|---|---|---|
| `expiresAtBusinessHours` | `WorkItemCreateRequest` | Resolved to absolute `expiresAt` via `BusinessCalendar` at create time. Precedence: absolute field wins if both provided. |
| `claimDeadlineBusinessHours` | `WorkItemCreateRequest` | Resolved to absolute `claimDeadline` via `BusinessCalendar`. Same precedence rule. |
| `defaultExpiryBusinessHours` | `WorkItemTemplate` | Passed through to spawned/instantiated WorkItems. |
| `defaultClaimBusinessHours` | `WorkItemTemplate` | Passed through to spawned/instantiated WorkItems. |

`BusinessCalendar` SPI (`quarkus-work-api`): `addBusinessDuration(Instant, Duration, ZoneId)` and `isBusinessHour(Instant, ZoneId)`. Default implementation reads `quarkus.work.business-hours.*` config (timezone, start/end, work-days, holidays list). `HolidayCalendar` SPI (`quarkus-work-api`): pluggable holiday data source. Default: static config list. Optional: iCal feed via `quarkus.work.business-hours.holiday-ical-url`. Override: provide any `@ApplicationScoped HolidayCalendar` bean — takes precedence via `@DefaultBean` producer.

**WorkItemLabel (`runtime/model/`)** — each entry:

| Field | Type | Notes |
|---|---|---|
| `path` | String | `/`-separated label path, e.g. `legal/contracts/nda` |
| `persistence` | `LabelPersistence` | `MANUAL` (human-applied, never touched by engine) or `INFERRED` (filter-applied, recomputed on every mutation) |
| `appliedBy` | String | userId (MANUAL) or filterId (INFERRED) — audit trail |

Vocabulary (`LabelVocabulary` + `LabelDefinition`) enforces path declarations at four scopes: `GLOBAL → ORG → TEAM → PERSONAL`. A GLOBAL vocabulary is seeded with common labels on first Flyway run. See `docs/specs/2026-04-15-queues-design.md` for full label model and filter engine design.

**WorkItemStatus — aligned with quarkus-flow (`SUSPENDED`, `CANCELLED` identical):**

| Status | Meaning |
|---|---|
| `PENDING` | Available for claiming; no assignee, or returned to pool |
| `ASSIGNED` | Claimed; not yet actively working |
| `IN_PROGRESS` | Being worked (≈ `RUNNING` in quarkus-flow) |
| `COMPLETED` | Successfully resolved |
| `REJECTED` | Human declined or declared uncomplete-able (≈ `FAULTED` in quarkus-flow) |
| `DELEGATED` | Transitional: ownership transferred, pending new assignment |
| `SUSPENDED` | On hold; will resume (identical to quarkus-flow) |
| `CANCELLED` | Externally cancelled by system or admin (identical to quarkus-flow) |
| `EXPIRED` | Passed completion deadline; triggers escalation policy |
| `ESCALATED` | Escalation policy has fired; terminal or awaiting admin action |

**Lifecycle transitions:**
```
PENDING → ASSIGNED (claim) | CANCELLED (admin)
ASSIGNED → IN_PROGRESS (start) | DELEGATED→PENDING | RELEASED→PENDING
         | SUSPENDED | CANCELLED (admin)
IN_PROGRESS → COMPLETED | REJECTED | DELEGATED→PENDING | SUSPENDED | CANCELLED (admin)
SUSPENDED → ASSIGNED | IN_PROGRESS (resume to prior state) | CANCELLED (admin)
PENDING | ASSIGNED | IN_PROGRESS | SUSPENDED → EXPIRED → ESCALATED
```

**DelegationState transitions:**
```
(null) → PENDING (delegate op) → RESOLVED (delegate completes) → (null) (owner confirms)
```

### WorkItemFormSchema (`runtime/model/`) — Epic #98, Issue #107

JSON Schema definitions for WorkItem payload and resolution, keyed optionally by category.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID PK | Set in `@PrePersist` |
| `name` | String | Display name; required |
| `category` | String | Optional — null means global/catch-all; index on `category` |
| `payloadSchema` | TEXT | JSON Schema (draft-07) for `WorkItem.payload`; stored verbatim |
| `resolutionSchema` | TEXT | JSON Schema for resolution submitted on complete; stored verbatim |
| `schemaVersion` | String | Free-form (e.g. "1.0"); optional |
| `createdBy` | String | Required |
| `createdAt` | Instant | Set in `@PrePersist` |

WorkItemFormSchema has **no foreign-key relationship to WorkItem** — it is a category-level definition with independent lifecycle. Deleting a schema does not affect WorkItems; creating a WorkItem does not require a schema to exist.

### AuditEntry (`runtime/model/`)
Append-only event log: `workItemId`, `event`, `actor`, `detail` (JSON), `occurredAt`.

Audit event values (aligned with quarkus-flow task event naming):
`CREATED` | `ASSIGNED` | `STARTED` | `COMPLETED` | `REJECTED` | `DELEGATED` | `RELEASED` | `SUSPENDED` | `RESUMED` | `CANCELLED` | `EXPIRED` | `ESCALATED`

---

## Storage SPI

**`AuditQuery` (`runtime.repository/`)** — value object for cross-WorkItem audit searches (Issue #109):

| Field | Default | Notes |
|---|---|---|
| `actorId` | null | Exact match on actor |
| `from` / `to` | null | Inclusive date range on occurredAt |
| `event` | null | Exact match on event type |
| `category` | null | Filters via subquery on WorkItem.category |
| `page` / `size` | 0 / 20 | Offset pagination; size capped at 100 |

Two interfaces in `runtime.repository` allow pluggable persistence:

| Interface | Default impl | Purpose |
|---|---|---|
| `WorkItemStore` | `JpaWorkItemStore` | `put(WorkItem)`, `get(UUID)`, `scan(WorkItemQuery)`, `scanAll()`. `WorkItemQuery` static factories: `inbox()`, `expired()`, `claimExpired()`, `byLabelPattern()`, `all()` — aligned with KV store semantics, backend-agnostic. |
| `AuditEntryStore` | `JpaAuditEntryStore` | append, findByWorkItemId |

Default JPA implementations are `@ApplicationScoped`. Alternatives (in-memory, MongoDB, Redis)
override via `@Alternative @Priority(1)`. The `quarkus-work-testing` module provides
`InMemoryWorkItemStore` + `InMemoryAuditEntryStore` — no datasource required,
enabling plain unit tests without `@QuarkusTest`.

---

## Services

| Service | Package | Responsibilities |
|---|---|---|
| `WorkItemService` | `runtime.service` | Create, assign, claim, complete, reject, delegate; enforces status transitions. Overloaded `complete(+rationale, +planRef)` and `reject(+rationale)` pass through to ledger for GDPR Article 22 compliance. |
| `FormSchemaValidationService` | `runtime.service` | Pure JSON Schema draft-07 validator (networknt). No DB access — callers resolve the schema string. Returns `List<String>` violations; null/blank JSON → empty list (skip). Used by `WorkItemResource` on create (payload) and complete (resolution). |
| `WorkItemAssignmentService` | `runtime.service` | Orchestrates worker selection on CREATED/RELEASED/DELEGATED. Resolves candidates from `candidateUsers` (direct) + `WorkerRegistry` (groups), populates `activeWorkItemCount`, filters by `requiredCapabilities`, calls `WorkerSelectionStrategy.select()`, applies `AssignmentDecision` (sets status=ASSIGNED + assignedAt when pre-assigning). |
| `ClaimFirstStrategy` | `io.quarkiverse.work.core.strategy` | Default `WorkerSelectionStrategy` no-op — `AssignmentDecision.noChange()`; pool stays open for claim-first. Activated by `quarkus.work.routing.strategy=claim-first`. |
| `LeastLoadedStrategy` | `io.quarkiverse.work.core.strategy` | Default `WorkerSelectionStrategy` — pre-assigns to candidate with fewest ASSIGNED/IN_PROGRESS/SUSPENDED WorkItems. Activated by `quarkus.work.routing.strategy=least-loaded` (default). `noChange()` when no candidates. |
| `NoOpWorkerRegistry` | `io.quarkiverse.work.core.strategy` | Default `WorkerRegistry` — returns empty list for all groups (groups stay claim-first until app registers real resolver). |
| `JpaWorkloadProvider` | `runtime.service` | Counts active (ASSIGNED/IN_PROGRESS/SUSPENDED) WorkItems for a worker; implements `WorkloadProvider` from `quarkus-work-api`. |
| `ExpiryCleanupJob` | `runtime.service` | `@Scheduled` — marks expired WorkItems, fires EscalationPolicy |
| `EscalationPolicy` | `io.quarkiverse.work.api` | SPI — single `escalate(WorkLifecycleEvent)` method; pluggable: notify, reassign, auto-reject |

---

## Ledger Module

The optional `quarkus-work-ledger` module records every WorkItem lifecycle transition as an
immutable `WorkItemLedgerEntry`. It is activated by adding the module to the classpath — the
core extension is completely unchanged whether the module is present or not.

### Dependency on quarkus-ledger (ADR-0001)

`quarkus-work-ledger` depends on `io.quarkiverse.ledger:quarkus-ledger` — a domain-agnostic
shared library providing `LedgerEntry`, `LedgerAttestation`, `ActorTrustScore`,
`TrustScoreComputer`, `LedgerHashChain`, and their repositories. This allows CaseHub and
Qhorus to adopt the same ledger infrastructure without depending on WorkItems.

`WorkItemLedgerEntry` extends `LedgerEntry` via JPA JOINED inheritance, adding only
`commandType` and `eventType`. All common fields live in the `ledger_entry` base table.

### actorType derivation

`LedgerEventCapture` derives `actorType` from the `actorId` prefix convention:

| Prefix | ActorType |
|---|---|
| `agent:` | `AGENT` |
| `system:` | `SYSTEM` |
| *(anything else)* | `HUMAN` |

This allows AI agents and scheduled jobs to be identified without a separate API parameter.

### Integration point

`LedgerEventCapture` is an `@Observes WorkItemLifecycleEvent` CDI bean — the sole coupling
between the core and the ledger module. If the module is absent, events fire into the void.

---

## REST API Surface

`WorkItemResource` at `/workitems` (core):

| Endpoint | Transition | Notes |
|---|---|---|
| `POST /` | → PENDING | Create WorkItem; accepts `labels` list (MANUAL only). If a `WorkItemFormSchema` exists for the `category`, validates `payload` against `payloadSchema` → 400 with `violations[]` on breach. |
| `GET /inbox` | — | `?assignee&candidateGroup&candidateUser&status&priority&category&followUp` |
| `GET /` | — | List all (admin); `?label=pattern` filters by label wildcard |
| `GET /{id}` | — | Full WorkItem + audit log + labels |
| `PUT /{id}/claim` | PENDING → ASSIGNED | Caller becomes assignee |
| `PUT /{id}/start` | ASSIGNED → IN_PROGRESS | Begin work |
| `PUT /{id}/complete` | IN_PROGRESS → COMPLETED | Body: resolution JSON. If a `WorkItemFormSchema` exists for the category, validates `resolution` against `resolutionSchema` → 400 with `violations[]` on breach. |
| `PUT /{id}/reject` | ASSIGNED\|IN_PROGRESS → REJECTED | Body: reason |
| `PUT /{id}/delegate?to=Y` | → DELEGATED → PENDING | Sets owner on first delegation |
| `PUT /{id}/release` | ASSIGNED → PENDING | Relinquish to candidate pool |
| `PUT /{id}/suspend` | ASSIGNED\|IN_PROGRESS → SUSPENDED | Body: reason |
| `PUT /{id}/resume` | SUSPENDED → prior state | Restores ASSIGNED or IN_PROGRESS |
| `PUT /{id}/cancel` | any → CANCELLED | Admin; body: reason |
| `POST /{id}/labels` | — | Add MANUAL label; path must be in vocabulary |
| `DELETE /{id}/labels?path=` | — | Remove MANUAL label; returns 404 if absent |

`VocabularyResource` at `/vocabulary` (core):

| Endpoint | Notes |
|---|---|
| `GET /` | List all label definitions accessible to caller |
| `POST /{scope}` | Add label definition; GLOBAL scope currently supported |

`WorkItemFormSchemaResource` at `/workitem-form-schemas` (core, Epic #98):

| Endpoint | Notes |
|---|---|
| `POST /workitem-form-schemas` | Create a schema; `name` + `createdBy` required; `category` + both schemas + `schemaVersion` optional |
| `GET /workitem-form-schemas` | List all, ordered by name |
| `GET /workitem-form-schemas?category=X` | List schemas for category X |
| `GET /workitem-form-schemas/{id}` | Get single schema; 404 if not found |
| `DELETE /workitem-form-schemas/{id}` | Delete schema; 204/404; independent of WorkItem lifecycle |

`FilterRuleResource` at `/filter-rules` (`quarkus-work-core` module, Epic #100):

| Endpoint | Notes |
|---|---|
| `POST /filter-rules` | Create dynamic rule; `name` + `condition` required |
| `GET /filter-rules` | List all dynamic rules |
| `GET /filter-rules/{id}` | Get single rule; 404 if not found |
| `DELETE /filter-rules/{id}` | Delete rule; 204/404 |
| `GET /filter-rules/permanent` | List CDI-produced permanent filters with enabled state |
| `PUT /filter-rules/permanent/enabled?name=X` | Toggle permanent filter at runtime; 200/400/404 |

`AuditResource` at `/audit` (core, Epic #99, Issue #109):

| Endpoint | Notes |
|---|---|
| `GET /audit` | Paginated cross-WorkItem audit history; all params optional |
| `GET /audit?actorId=X` | Exact match on actor field |
| `GET /audit?event=COMPLETED` | Filter by event type |
| `GET /audit?from=ISO8601&to=ISO8601` | Date range on occurredAt |
| `GET /audit?category=finance` | WorkItems in that category (subquery) |
| `GET /audit?page=N&size=M` | Offset pagination; size capped at 100; default page=0, size=20 |

Response envelope: `{entries: [...], page, size, total}`. Each entry includes `id`, `workItemId`, `event`, `actor`, `detail`, `occurredAt`.

`ReportResource` at `/workitems/reports` (core, Epic #99, Issues #110–111):

| Endpoint | Notes |
|---|---|
| `GET /workitems/reports/sla-breaches?from=&to=&category=&priority=` | WorkItems that missed `expiresAt`; returns `items[]` + `summary{totalBreached,avgBreachDurationMinutes,byCategory}` |
| `GET /workitems/reports/actors/{actorId}?from=&to=&category=` | Actor performance: `totalAssigned`, `totalCompleted`, `totalRejected`, `avgCompletionMinutes` (null if no completions), `byCategory` map |

`quarkus-work-queues` adds `/filters`, `/queues`, `/workitems/{id}/pickup`, and `/workitems/{id}/relinquishable`. See `docs/api-reference.md` for full queue API documentation.

---

## Configuration

`WorkItemsConfig` — `@ConfigMapping(prefix = "quarkus.work")`:

| Property | Default | Meaning |
|---|---|---|
| `quarkus.work.default-expiry-hours` | 24 | Default completion deadline |
| `quarkus.work.default-claim-hours` | 4 | Default claim deadline (0 = no claim deadline) |
| `quarkus.work.escalation-policy` | notify | Completion expiry: `notify`, `reassign`, `auto-reject` |
| `quarkus.work.claim-escalation-policy` | notify | Claim deadline breach: `notify`, `reassign` |
| `quarkus.work.cleanup.expiry-check-seconds` | 60 | Expiry/claim-deadline job interval |
| `quarkus.work.routing.strategy` | least-loaded | Worker selection: `least-loaded` (default — pre-assigns to fewest-active candidate) or `claim-first` (pool stays open). Override with CDI `@Alternative WorkerSelectionStrategy`. |

Consuming app owns all datasource config.

---

## Build Roadmap

| Phase | Status | What |
|---|---|---|
| **1 — Core data model** | ✅ Complete | Storage SPI, JPA defaults, InMemory (testing module), WorkItem + AuditEntry entities, Flyway V1+V2+V3, WorkItemService, WorkItemsConfig, WorkItemLabel (MANUAL/INFERRED), LabelVocabulary + LabelDefinition |
| **2 — REST API** | ✅ Complete | WorkItemResource — 13 endpoints, DTOs, exception mappers |
| **3 — Lifecycle engine** | ✅ Complete | ExpiryCleanupJob, ClaimDeadlineJob, EscalationPolicy SPI + 3 implementations |
| **4 — CDI events** | ✅ Complete | WorkItemLifecycleEvent on all transitions; rationale + planRef fields |
| **5 — Quarkus-Flow integration** | ✅ Complete | `quarkus-work-flow` — WorkItemsFlow DSL, HumanTaskFlowBridge, Uni<String> suspension |
| **6 — Ledger module** | ✅ Complete | `quarkus-work-ledger` — command/event model, hash chain, attestation, EigenTrust; optional, zero core impact |
| **7 — Label-based queues** | ✅ Complete | `quarkus-work-queues` — label model (MANUAL/INFERRED), vocabulary (GLOBAL→PERSONAL scopes), filter engine (JEXL/JQ/Lambda, multi-pass propagation, cascade delete via FilterChain), QueueView named queries, soft assignment. See ADR-0002 and `docs/specs/2026-04-15-queues-design.md` |
| **8 — Native image** | ✅ Complete | GraalVM 25 native build, 19 @QuarkusIntegrationTest tests, 0.084s startup |
| **Examples** | ✅ Complete | `quarkus-work-examples` (4 ledger scenarios) + `quarkus-work-flow-examples` (WorkItemsFlow DSL showcase) + `quarkus-work-queues-examples` (5 queue scenarios: triage cascade, legal routing, finance approval, security escalation, document review pipeline) |
| **Dashboard** | ✅ Complete | `quarkus-work-queues-dashboard` — Tamboui TUI inside Quarkus via `@QuarkusMain`; live queue board, step-by-step scenario control, 6 Pilot end-to-end tests passing headlessly via `TuiTestRunner` (`TestBackend` is in `tamboui-core:test-fixtures`) |
| **9 — Form Schema** | ✅ Complete | Epic #98: `WorkItemFormSchema` entity + CRUD API (#107 ✅), payload/resolution validation (#108 ✅). UI devs can GET the schema for a category and auto-generate validated forms. |
| **10 — Audit History Query API** | ✅ Complete | Epic #99: `GET /audit` cross-WorkItem query with actorId/event/date/category filters + pagination (#109 ✅), SLA breach report (#110 ✅), actor performance summary (#111 ✅). V12 indexes. |
| **11 — Confidence-Gated Routing** | ✅ Complete | Epic #100: `confidenceScore` on WorkItem + V13 (#112 ✅), `quarkus-work-filter-registry` module with `FilterAction` SPI + JEXL engine + permanent/dynamic registry (#113 ✅), `quarkus-work-ai` `LowConfidenceFilterProducer` (#114 ✅). |
| **12 — WorkerSelectionStrategy** | ✅ Complete | Epics #100/#102: `quarkus-work-api` shared SPI module (#115 ✅), `WorkItemAssignmentService` + `LeastLoadedStrategy` + `ClaimFirstStrategy` + `NoOpWorkerRegistry` wired into create/release/delegate (#116 ✅). `RoundRobinStrategy` deferred (#117). |
| **13 — quarkus-work separation** | ✅ Complete | `quarkus-work-api` (shared SPI contracts) and `quarkus-work-core` (WorkBroker + generic filter engine) extracted. `quarkus-work-api` renamed to `quarkus-work-api`; `quarkus-work-filter-registry` dissolved into `quarkus-work-core`. CaseHub can now depend on `quarkus-work-core` for `WorkBroker` without pulling in human-inbox specifics. Issue #118. |
| **13b — Semantic Skill Matching** | ✅ Complete | `SkillProfile` + `SkillProfileProvider` + `SkillMatcher` SPIs in `quarkus-work-api`. `SelectionContext` gains `title` and `description`. `EmbeddingSkillMatcher` (`dev.langchain4j` cosine similarity). `WorkerProfileSkillProfileProvider` (default, DB-backed), `CapabilitiesSkillProfileProvider` + `ResolutionHistorySkillProfileProvider` (`@Alternative`). `SemanticWorkerSelectionStrategy` auto-activates via `@Alternative @Priority(1)`. `WorkerSkillProfile` entity + REST API at `/worker-skill-profiles`. Flyway V14. 48 tests in `quarkus-work-ai` (was 8). Issues #119 (composite provider) and #120 (fallback strategy) filed for future iteration. Issue #121. |
| **10 — CaseHub integration** | ⏸ Blocked | `quarkus-work-casehub` — CaseHub WorkerRegistry adapter (awaiting CaseHub stable API) |
| **10 — Qhorus integration** | ⏸ Blocked | `quarkus-work-qhorus` — MCP tools (awaiting Qhorus stable API) |
| **11 — ProvenanceLink** | ⏸ Blocked | Typed PROV-O causal graph — awaiting CaseHub + Qhorus integrations (issue #39) |

---

## Testing Strategy

Three tiers:

**Unit tests** (no Quarkus boot):
- Use `InMemoryWorkItemStore` from `quarkus-work-testing`
- Pure logic functions (e.g. `QueueBoardBuilder`, `LabelVocabularyService.matchesPattern()`)
- No datasource, no Flyway, instant execution

**Integration tests** (`@QuarkusTest`):
- H2 in-memory datasource; Flyway runs all migrations at boot
- `@TestTransaction` per test method — each test rolls back, no data leakage
- Exercise full stack: REST → Service → JPA → H2
- TDD: write tests first (RED), implement, run to GREEN

**TUI end-to-end tests** (Tamboui Pilot):
- `TuiTestRunner.runTest(dashboard::handleEvent, dashboard::renderBoard)` runs the TUI headlessly on a background thread
- `Pilot.press('s')` + `Pilot.pause()` simulates key input; assert on CDI bean state
- 6 tests in `QueueDashboardTest` — all passing. `TestBackend` is in `tamboui-core:test-fixtures` (not `tamboui-tui:test-fixtures`); both must be declared as test dependencies

**Current test totals (all modules, 0 failures):**

| Module | Tests |
|---|---|
| quarkus-work-api | 15 |
| quarkus-work-core | 38 |
| runtime | 548 |
| work-flow | 32 |
| quarkus-work-ledger | 75 |
| quarkus-work-queues | 82 |
| quarkus-work-ai | 48 |
| quarkus-work-examples | 37 |
| quarkus-work-flow-examples | 2 |
| quarkus-work-queues-examples | 37 |
| quarkus-work-queues-dashboard | 20 |
| quarkus-work-persistence-mongodb | 27 |
| quarkus-work-issue-tracker | 23 |
| testing | 16 |
| integration-tests | 19 (native) |
| **Total** | **1019+** |
