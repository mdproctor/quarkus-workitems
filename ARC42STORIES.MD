# CaseHub Work — ARC42STORIES.MD

**Spec:** Arc42Stories v0.1
**Profile:** CaseHub — Foundation tier
**Profile ref:** `../parent/docs/arc42stories-casehub-profile.md` · fallback: `https://raw.githubusercontent.com/casehubio/parent/main/docs/arc42stories-casehub-profile.md`
**Build position:** Foundation — depends on `casehub-platform-api` only (core); `casehub-ledger` optional
**Consumed by:** `casehub-engine` (work-adapter), `casehub-clinical`, `devtown`, `casehub-life`
**Depends on:** `casehub-platform-api` (compile, `api/` module only)

---

## §1 Introduction and Goals

### Description

CaseHub Work is a Quarkus extension that provides human-scale WorkItem lifecycle management: expiry, delegation, escalation, priority, SLA tracking, and audit trail. It is not a workflow engine, not a case manager, and not an agent mesh — those concerns belong to `casehub-engine`, `casehub-qhorus`, and Quarkus-Flow respectively. Any Quarkus application embeds it via CDI and REST resources without taking a dependency on any other casehubio module.

### Stakeholders

| Stakeholder | Interest |
|---|---|
| Quarkus app developer | Embeds the extension; configures `casehub.work.*` properties; wires CDI beans |
| Consumer repo (casehub-engine, casehub-clinical, devtown) | Calls REST or CDI API to create, claim, delegate, and complete WorkItems |
| Human task actor | Receives, claims, and resolves WorkItems via the inbox REST surface |
| AI agent | Polls or subscribes to WorkItem queues; delegates or escalates on SLA breach |
| Platform team | Maintains lifecycle contracts, SPI stability, and cross-repo protocol compliance |

### Quality Goals

| Priority | Goal | Scenario |
|---|---|---|
| 1 | SLA correctness | An expired WorkItem triggers its breach policy within one scheduler cycle, with no manual intervention |
| 2 | Isolation | The core extension compiles and passes unit tests without `casehub-ledger`, `casehub-qhorus`, or `casehub-engine` on the classpath |
| 3 | Zero-datasource unit testing | `WorkItemServiceTest` runs without Quarkus boot via `InMemoryWorkItemStore`, completing in under 1 s |

### Artifact Schema

| Artifact type | Format | Example | Where it lives |
|---|---|---|---|
| Issue | `#NNN` or `casehubio/work#NNN` | `#246` | GitHub Issues |
| ADR | `ADR-NNNN` | `ADR-0005` | `docs/adr/` |
| Garden entry | `GE-YYYYMMDD-XXXXXX` | `GE-20260522-9cd6d5` | `~/.hortora/garden/` |
| Protocol | `PP-YYYYMMDD-XXXXXX` | `PP-20260525-607b33` | `casehub-parent/docs/protocols/` |
| Design spec | `YYYY-MM-DD-topic-design` | `2026-04-14-tarkus-design` | `docs/specs/` |

---

## §2 Constraints

### Platform

| Constraint | Value |
|---|---|
| Java | 21 language level, running on JVM 26 |
| Framework | Quarkus 3.32.2 |
| Native target | GraalVM 25; verified startup 0.084 s |
| Build | `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install` — use `mvn`, not `./mvnw` |

### Architectural Constraints

- Zero casehubio core deps: the `runtime/` module depends only on `casehub-platform-api`; all other casehubio integrations are optional or in separate modules
- Module naming: short names (`api/`, `runtime/`, `deployment/`) — no repo-prefix repetition (e.g. not `casehub-work-api/`)
- Flyway path scoping: all migrations live at `classpath:db/work/migration/` (PP-20260525-607b33)
- No auth on REST resources: consuming applications add `@RolesAllowed`; this extension ships auth-retrofit-readiness stubs only
- Test datasource: H2 `MODE=PostgreSQL` for unit and module tests; Testcontainers (real PostgreSQL) for dialect validation

---

## §3 Context and Scope

```mermaid
C4Context
  title casehub-work — System Context
  Person(actor, "Human Actor / AI Agent", "Claims, works, delegates, and resolves WorkItems")

  System(work, "casehub-work", "Human task lifecycle: WorkItem inbox, SLA enforcement, delegation, escalation, audit trail. Standalone or integrated.")

  System_Ext(engine, "casehub-engine", "Consumes casehub-work-core (WorkBroker) and casehub-work-api (WorkItemCallerRef). Drives WorkItem creation; receives lifecycle events.")
  System_Ext(ledger, "casehub-ledger", "Provides LedgerEntry base class; WorkItemLedgerEntry extends it. Trust scoring reads attestation records.")
  System_Ext(platform, "casehub-platform-api", "Provides Path, Preferences, ActorType — used in SlaBreachContext and worker selection SPI signatures.")
  System_Ext(consumers, "casehub-clinical, devtown, casehub-life", "Application-tier consumers embedding casehub-work runtime for domain WorkItem flows.")

  Rel(actor, work, "REST /workitems — claim, start, complete, delegate")
  Rel(engine, work, "Creates WorkItems via WorkBroker; observes WorkItemLifecycleEvent CDI")
  Rel(work, ledger, "WorkItemLedgerEntry (optional module)")
  Rel(work, platform, "SlaBreachContext, Path, ActorType in SPI signatures")
  Rel(consumers, work, "Runtime dependency")
```

### Boundary Rules

What casehub-work explicitly does NOT do:

- Orchestrate case flow or interpret case state
- Interpret `callerRef` content — stored and echoed opaquely; convention: `casehub-engine` sets `"case:{caseId}/pi:{planItemId}"` (HumanTask) or `"case:{caseId}/gate:{gateId}"` (ActionGate)
- Provision AI agents — casehub-engine and Claudony own that
- Decide when to spawn child WorkItems — callers drive spawn via `SpawnPort`
- Implement trust scoring — casehub-ledger owns this; casehub-work fires events, ledger records and scores
- Determine when heterogeneous plan items all complete — casehub-engine; homogeneous M-of-N IS casehub-work

---

## §4 Solution Strategy

Foundation modules define their own layer taxonomy. casehub-work's layers represent the
internal architectural concerns added incrementally across 35 build Chapters:

### Layer Taxonomy

| Layer | Concern |
|---|---|
| L1 Domain Baseline | WorkItem + AuditEntry entities, Storage SPI, JPA defaults, WorkItemService — the inbox foundation |
| L2 REST API | `casehub-work-rest` module — WorkItemResource (7 sub-resources), DTOs, exception mappers, OpenAPI. Opt-in via dependency; extracted from runtime in #292 |
| L3 Lifecycle Engine | ExpiryTimerJob, ClaimDeadlineTimerJob, ClaimSlaPolicy SPI, SlaBreachPolicy SPI, CDI lifecycle events |
| L4 Label System | LabelVocabulary, MANUAL/INFERRED label persistence, LabelRuleEngine (JEXL/JQ/Lambda), QueueView |
| L5 Ledger Integration | WorkItemLedgerEntry (JOINED from LedgerEntry), hash chain, peer attestation, EigenTrust |
| L6 Distribution | WorkItemEventBroadcaster SPI, LocalWorkItemEventBroadcaster @DefaultBean, PostgreSQL LISTEN/NOTIFY broadcaster |
| L7 Optional Modules | SLA reports, AI (semantic routing + LLM assist), notifications, issue tracker, MongoDB persistence, Quarkus-Flow bridge |

### Chapter Sequencing Rationale

Hard dependencies — order is non-negotiable:

- C1 before C2: REST requires the persisted WorkItem entity and service layer at runtime
- C2 before C3: lifecycle transitions require service methods to exist first
- C3 before C4: CDI events are emitted inside service transitions; transitions must exist
- C6 (Ledger) after C4: `LedgerEventCapture @Observes WorkItemLifecycleEvent` — the event bus must exist first
- C7 (Label Queues) before C16 (Confidence Routing): confidence-gated routing extends the label rule engine from C7
- C18 (Module Separation) before C19 (Semantic AI + LLM Assist): semantic AI depends on the api/core SPI split
- C26 (Broadcaster SPI) before C27 (Distributed SSE): PostgreSQL broadcaster implements the SPI extracted in C26
- C33 (SlaBreachPolicy SPI) before C35 (Status Lifecycle): status correctness fixes (#243 EXPIRED in isTerminal, #244 Exhausted) depend on the sealed `BreachDecision` type from C33

---

## §5 Building Block View

```mermaid
C4Container
  title casehub-work — Layer Architecture

  Container_Boundary(b1, "Layer: L1 Domain Baseline") {
    Container(api, "casehub-work-api", "Pure Java", "SPI contracts: WorkerSelectionStrategy, SlaBreachPolicy, ClaimSlaPolicy, ExclusionPolicy, SpawnPort, NotificationChannel, Capability, AuditEntryResponse")
    Container(core, "casehub-work-core", "Jandex library", "WorkBroker, LeastLoadedStrategy, ClaimFirstStrategy, RoundRobinStrategy, CapabilityValidator")
    Container(runtime, "casehub-work", "Quarkus extension", "WorkItem entity, WorkItemService, JpaWorkItemStore, AuditEntryStore, Flyway V1–V41")
    Container(deploy, "casehub-work-deployment", "Quarkus deployment", "Build-time processors, feature registration, native config")
    Container(pmem, "casehub-work-persistence-memory", "In-memory persistence", "InMemoryWorkItemStore, InMemoryAuditEntryStore, InMemoryWorkItemNoteStore, InMemoryIssueLinkStore @Alternative @Priority(100)")
  }

  Container_Boundary(b2, "Layer: L2 REST API") {
    Container(rest, "casehub-work-rest", "Jandex library", "JAX-RS resources (12), request/response DTOs, exception mappers, WorkItemMapper — opt-in REST surface")
  }

  Container_Boundary(b4, "Layer: L4 Label System") {
    Container(queues, "casehub-work-queues", "Optional Quarkus extension", "LabelVocabulary, LabelRuleEngine (JEXL/JQ/Lambda), QueueView, WorkItemQueueEvent")
    Container(qdash, "casehub-work-queues-dashboard", "Optional Quarkus extension", "SSE-based queue dashboard UI")
  }

  Container_Boundary(b5, "Layer: L5 Ledger Integration") {
    Container(ledger, "casehub-work-ledger", "Optional Quarkus extension", "WorkItemLedgerEntry extends LedgerEntry; hash chain, attestation, EigenTrust")
  }

  Container_Boundary(b6, "Layer: L6 Distribution") {
    Container(pb, "casehub-work-postgres-broadcaster", "Optional Quarkus extension", "PostgresWorkItemEventBroadcaster — LISTEN/NOTIFY on casehub_work_events")
    Container(qpb, "casehub-work-queues-postgres-broadcaster", "Optional Quarkus extension", "PostgresWorkItemQueueEventBroadcaster — LISTEN/NOTIFY on casehub_work_queue_events")
  }

  Container_Boundary(b7, "Layer: L7 Optional Modules") {
    Container(ai, "casehub-work-ai", "Optional Quarkus extension", "SemanticWorkerSelectionStrategy, EmbeddingSkillMatcher, ResolutionSuggestionService, EscalationSummaryObserver")
    Container(reports, "casehub-work-reports", "Optional Quarkus extension", "SLA compliance reports: breach rates, actor performance, throughput, queue health")
    Container(notif, "casehub-work-notifications", "Optional Quarkus extension", "HTTP webhook, Slack, Teams outbound notifications")
    Container(it, "casehub-work-issue-tracker", "Optional Quarkus extension", "IssueTrackerProvider SPI, GitHub/Jira webhook handlers, IssueLinkStore, NormativeResolution")
    Container(mongo, "casehub-work-persistence-mongodb", "Optional Quarkus extension", "MongoDB WorkItemStore @Alternative @Priority(1)")
    Container(flow, "casehub-work-flow", "Optional Quarkus extension", "HumanTaskFlowBridge, WorkItemFlowEventListener — Quarkus-Flow CDI bridge")
  }

  Container_Boundary(b8, "Layer: L8 Federation") {
    Container(federation, "casehub-work-federation", "Optional Quarkus extension", "FederationGuardStore (@Decorator on WorkItemStore.put()), FederationProxyService (@Decorator on WorkItemOperations), FederationReceiver (inbound CloudEvents → shadow WorkItems), FederationEventRouter (outbound lifecycle events → subscriptions), FederationSubscriptionService, Flyway V8000")
    Container(client, "casehub-work-client", "Pure Java", "WorkItemClient — zero-dependency HTTP client for remote WorkItem operations (claim, complete, reject, delegate, release)")
  }
```

### Module Index

| Folder | Artifact | Type | Purpose |
|---|---|---|---|
| `api/` | `casehub-work-api` | Pure Java SPI | WorkerSelectionStrategy, SlaBreachPolicy, BreachDecision (sealed), ClaimSlaPolicy, ExclusionPolicy, SpawnPort, NotificationChannel, Capability, WorkItemCallerRef; depends on `casehub-platform-api` |
| `core/` | `casehub-work-core` | Jandex library | WorkBroker, LeastLoadedStrategy, ClaimFirstStrategy, RoundRobinStrategy, CapabilityValidator; used by casehub-engine without pulling in JPA or REST |
| `runtime/` | `casehub-work` | Quarkus extension | WorkItem + AuditEntry entities, WorkItemService, WorkItemAssignmentService, ExpiryLifecycleService, Flyway V1–V41 at `db/work/migration/` |
| `rest/` | `casehub-work-rest` | Jandex library | JAX-RS resources (12), request/response DTOs, exception mappers, WorkItemMapper; opt-in REST surface extracted from runtime (#292) |
| `deployment/` | `casehub-work-deployment` | Quarkus deployment | Build-time processor, feature registration, `casehub.work.*` native config |
| `persistence-memory/` | `casehub-work-persistence-memory` | In-memory persistence | InMemoryWorkItemStore, InMemoryAuditEntryStore, InMemoryWorkItemNoteStore, InMemoryIssueLinkStore, InMemoryRoutingCursorStore — `@Alternative @Priority(100)` (Tier 3) |
| `queues/` | `casehub-work-queues` | Optional extension | LabelVocabulary, MANUAL/INFERRED label persistence, LabelRuleEngine (JEXL/JQ/Lambda), QueueView, WorkItemQueueEventBroadcaster SPI |
| `queues-dashboard/` | `casehub-work-queues-dashboard` | Optional extension | SSE queue dashboard UI |
| `ledger/` | `casehub-work-ledger` | Optional extension | WorkItemLedgerEntry (JOINED from LedgerEntry), LedgerEventCapture @Observes, EigenTrust; depends on `casehub-ledger` |
| `postgres-broadcaster/` | `casehub-work-postgres-broadcaster` | Optional extension | PostgresWorkItemEventBroadcaster @Alternative @Priority(1); no Flyway |
| `queues-postgres-broadcaster/` | `casehub-work-queues-postgres-broadcaster` | Optional extension | PostgresWorkItemQueueEventBroadcaster @Alternative @Priority(1); no Flyway |
| `ai/` | `casehub-work-ai` | Optional extension | SemanticWorkerSelectionStrategy @Alternative @Priority(1), EmbeddingSkillMatcher, ResolutionSuggestionService, EscalationSummaryObserver, Flyway V14 + V4001–V4002 |
| `reports/` | `casehub-work-reports` | Optional extension | SLA compliance REST reports, Caffeine 5-min TTL cache |
| `notifications/` | `casehub-work-notifications` | Optional extension | HTTP webhook, Slack, Teams; NotificationChannel SPI; Flyway V3000–V3001 |
| `issue-tracker/` | `casehub-work-issue-tracker` | Optional extension | IssueTrackerProvider SPI, IssueLinkStore SPI, GitHub/Jira webhook handlers, NormativeResolution; Flyway V5000–V5001 |
| `persistence-mongodb/` | `casehub-work-persistence-mongodb` | Optional extension | MongoDB WorkItemStore @Alternative @Priority(1) |
| `engine-adapter/` | `casehub-work-engine-adapter` | Optional extension | CaseHub engine bridge — HumanTask/ActionGate WorkItem lifecycle; CallerRef, PlanItemCompletionApplier, JpaPlanItemStore. InboundWorkItemSchedulerImpl (engine#974, #397) — converts inbound qhorus messages to WorkItems. WorkActorStateContributor (engine#974, #398) — contributes active work items to actor state view. Relocated from `casehub-engine-work-adapter` (#290) |
| `flow/` | `casehub-work-flow` | Optional extension | HumanTaskFlowBridge, WorkItemFlowEventListener |
| `federation/` | `casehub-work-federation` | Optional extension | Cross-service federation; `FederationGuardStore` (`@Decorator` on `WorkItemStore.put()`), `FederationProxyService` (`@Decorator` on `WorkItemOperations`), `FederationReceiver` (inbound CloudEvents → shadow WorkItems), `FederationEventRouter` (outbound lifecycle events → subscriptions), subscription model with filter-on-creation lock-on. Flyway V8000. |
| `client/` | `casehub-work-client` | Pure Java library | Lightweight REST client for remote WorkItem operations (claim, complete, reject, delegate, release). No JPA, no CDI, no Quarkus dependencies. Used by `FederationProxy` and standalone consumers. |
| `integration-tests/` | — | Black-box test suite | @QuarkusIntegrationTest + native image validation (25 tests) |
| `examples/` | — | Runnable demos | POST /examples/{name}/run scenario runner |
| `flow-examples/` | — | Flow demos | Quarkus-Flow integration examples |
| `queues-examples/` | — | Queue demos | Queue and filter usage examples |

### WorkItem Domain Model

**WorkItemStatus** (10 values from `runtime/src/main/java/io/casehub/work/runtime/model/WorkItemStatus.java`):

| Status | Meaning | isTerminal() | isActive() |
|---|---|---|---|
| `PENDING` | Available for claiming; no assignee | false | true |
| `ASSIGNED` | Claimed; work not yet started | false | true |
| `IN_PROGRESS` | Actively being worked | false | true |
| `DELEGATED` | Forwarded to named actor; pending acceptance | false | true |
| `SUSPENDED` | On hold; will resume | false | true |
| `COMPLETED` | Resolved successfully | true | false |
| `REJECTED` | Declined by actor | true | false |
| `CANCELLED` | Externally cancelled | true | false |
| `EXPIRED` | Completion deadline passed | true | false |
| `ESCALATED` | All SLA breach policy branches exhausted; operator intervention required | true | false |

**Lifecycle transitions:**
```
PENDING → ASSIGNED (claim) | CANCELLED
ASSIGNED → IN_PROGRESS (start) | DELEGATED | RELEASED→PENDING | SUSPENDED | CANCELLED
IN_PROGRESS → COMPLETED | REJECTED | DELEGATED | SUSPENDED | CANCELLED
SUSPENDED → ASSIGNED | IN_PROGRESS (resume to priorStatus) | CANCELLED
DELEGATED → ASSIGNED (accept-delegation) | PENDING (decline, POOL path) | ASSIGNED (decline, DELEGATOR path)
any active → EXPIRED (SLA Fail) | PENDING (SLA EscalateTo) | ESCALATED (Chained exhausted)
```

---

## §6 Runtime View

### Scenario 1 — WorkItem creation with assignment

Actor or system POSTs to `POST /workitems`. `WorkItemService` validates the request against the template's `inputDataSchema` (via `FormSchemaValidationService` if set), persists the WorkItem as PENDING via `JpaWorkItemStore`, fires `WorkItemLifecycleEvent(CREATED)`. `WorkItemAssignmentService` observes CREATED and calls `WorkBroker` with the configured `WorkerSelectionStrategy`. If a pre-assignment resolves, status transitions to ASSIGNED; otherwise the item stays in the pool. If `casehub-work-ledger` is on the classpath, `LedgerEventCapture @Observes` fires asynchronously and appends a `WorkItemLedgerEntry`.

### Scenario 2 — SLA breach → Fail

`ExpiryTimerJob` fires on schedule via `@Scheduled`. It calls `ExpiryLifecycleService.checkExpired()`, which scans all expired WorkItems and calls `SlaBreachPolicy.onBreach(SlaBreachContext)` per item:

- `BreachDecision.Fail` → WorkItem transitions to EXPIRED (terminal); `EXPIRED` audit entry written
- `BreachDecision.EscalateTo(groups, deadline)` → item returns to PENDING with new candidate groups and deadline; `SLA_REASSIGNED` audit entry written; `WorkItemAssignmentService` pre-assigns via `SLA_ESCALATED` trigger
- `BreachDecision.Chained` → primary decision tried, fallback tried on failure; both exhausted → `executeExhausted()` → WorkItem transitions to ESCALATED (terminal)

**Note:** `SlaBreachEvent` CDI fires on all `BreachDecision` outcomes (Fail, EscalateTo, Exhausted, Extend), not only Fail.

`BreachExecutionFailed` exceptions are caught at item level — one misconfigured policy writes `BREACH_POLICY_MISCONFIGURED` audit and skips the item without rolling back the batch.

### Scenario 3 — Delegation accept/decline

Actor A (ASSIGNED or IN_PROGRESS) calls `PUT /workitems/{id}/delegate` → WorkItem transitions to DELEGATED, `claimDeadline` cleared. Actor B calls `PUT /workitems/{id}/accept-delegation` → ASSIGNED to B. Or Actor B calls `PUT /workitems/{id}/decline-delegation` → resolves `delegationDeclineTarget`: instance field `POOL` → PENDING (back to original candidate pool); instance field `DELEGATOR` → ASSIGNED back to Actor A. If no instance field, reads scope preference via `DeclineTarget.KEY` (default POOL).

---

## §7 Deployment View

```mermaid
C4Deployment
  title casehub-work — Deployment (Standard + Distributed)
  Deployment_Node(quarkus1, "Quarkus Node 1") {
    Container(app1, "Application + casehub-work", "JVM or Native", "Serves REST API; fires lifecycle events")
  }
  Deployment_Node(quarkus2, "Quarkus Node 2") {
    Container(app2, "Application + casehub-work", "JVM or Native", "Serves REST API; fires lifecycle events")
  }
  Deployment_Node(pg, "PostgreSQL") {
    ContainerDb(db, "Database", "PostgreSQL", "work_item, audit_entry + all module tables; Flyway V1–V36")
    ContainerDb(notify, "LISTEN/NOTIFY", "PostgreSQL", "casehub_work_events + casehub_work_queue_events channels")
  }
  Rel(app1, db, "Flyway + JPA")
  Rel(app2, db, "Flyway + JPA")
  Rel(app1, notify, "NOTIFY on lifecycle event (TransactionPhase.AFTER_SUCCESS)")
  Rel(notify, app2, "LISTEN → re-broadcast to local SSE clients")
```

### Deployment Variants

| Variant | Add to classpath | Capability gained |
|---|---|---|
| Minimal (evaluation) | `casehub-work` + H2 datasource | WorkItem lifecycle, SLA, REST API |
| Standard | `casehub-work` + PostgreSQL datasource | Full lifecycle with production datasource |
| Distributed cluster | + `casehub-work-postgres-broadcaster` | All nodes receive all SSE events via LISTEN/NOTIFY |
| Full audit | + `casehub-work-ledger` + `casehub-ledger` | Tamper-evident Merkle chain, EigenTrust scoring |
| AI routing | + `casehub-work-ai` + embedding provider | Semantic worker selection + LLM-assisted resolution |
| MongoDB | + `casehub-work-persistence-mongodb` | MongoDB WorkItemStore (drop-in, no datasource config change) |

---

## §8 Crosscutting Concepts

### Convention References

| Concern | Protocol |
|---|---|
| Module tier structure | module-tier-structure *(not yet written)* — pure-Java SPI / Jandex library / Quarkus extension three-tier rule |
| Flyway migrations | flyway-version-range-allocation *(not yet written)* — V1–V999 runtime; V2000+ ledger subclass; optional modules own dedicated ranges (ai: V14+V4001–V4002, notifications: V3000–V3001, issue-tracker: V5000) |
| Flyway path scoping | PP-20260525-607b33 — all migrations at `db/work/migration/`; consumers set `quarkus.flyway.locations=classpath:db/work/migration` explicitly; Flyway auto-registration does not exist |
| CDI displacement | alternative-extension-patterns *(not yet written)* — `@DefaultBean` displaced by `@Alternative @Priority(1)` via classpath presence; no consumer config change |
| SPI placement | `docs/PLATFORM.md` §Step 4 — consumer-facing SPIs in `api/`; `@DefaultBean` impls in `api/` when pure Java, in `runtime/` when JPA or config deps apply |
| Persistence backend priority | persistence-backend-cdi-priority *(not yet written)* — `@DefaultBean` → `@ApplicationScoped` → `@Alternative @Priority(1)` ladder |
| Auth readiness | auth-retrofit-readiness *(not yet written)* — no `@RolesAllowed` in the extension; REST resources stay thin enough for retrofit |
| Capability vocabulary | `docs/adr/0003-capability-vocabulary-as-validated-value-type.md`, `docs/adr/0004-capability-validation-mode-as-deployment-config.md` |

### Anti-Patterns

**Symptom:** Augmentation fails with `UnsatisfiedResolutionException` for `PreferenceProvider` while all `@QuarkusTest` tests pass.
**Cause:** `casehub-platform` mock module added as `<scope>test</scope>` in a module that declares `<goal>build</goal>` in the `quarkus-maven-plugin`. Production augmentation validates CDI without the test classpath — `MockPreferenceProvider @DefaultBean` is invisible at augmentation time.
**Fix:** Change `casehub-platform` to `<scope>runtime</scope>` in modules that run `quarkus:build`. Keep `<scope>test</scope>` in library and extension modules that do not run `quarkus:build`.

**Symptom:** After adding a new non-terminal `WorkItemStatus` value, the expiry scheduler silently skips items in that status.
**Cause:** `WorkItemQuery.expired()` and `WorkItemQuery.claimExpired()` filter on explicit status sets. A new active status absent from those sets is invisible to `ExpiryTimerJob`.
**Fix:** Whenever adding a new non-terminal `WorkItemStatus` value, update three places atomically: `WorkItemStatus.isActive()`, `WorkItemStatus.isTerminal()`, and the status predicates inside `WorkItemQuery.expired()` / `WorkItemQuery.claimExpired()`.

**Symptom:** SSE clients receive lifecycle events for WorkItems whose creating transaction rolled back.
**Cause:** A `WorkItemEventBroadcaster` observer fires during the transaction (before commit). If the transaction rolls back, the event was already dispatched.
**Fix:** Annotate all broadcaster `@Observes` methods with `during = TransactionPhase.AFTER_SUCCESS`. Never dispatch from `@Observes` without this parameter.

**Symptom:** Two concurrent claim requests on different cluster nodes both succeed — the same WorkItem is assigned to two actors.
**Cause:** `WorkItemStore.put()` without optimistic locking allows concurrent writers to overwrite each other's state.
**Fix:** `WorkItemEntity` carries `@Version long version`; concurrent claim produces `OptimisticLockException` which `WorkItemResource` maps to HTTP 409 Conflict. The second claimer must retry claim.

---

## §9 Journeys and Chapters

### §9.1 Journey Overview

| Journey | Description | Chapters | Status |
|---|---|---|---|
| Core Platform | Domain baseline, REST API, lifecycle engine, CDI events, Quarkus-Flow, ledger, label queues, native image, WorkItemTemplate, model enrichment, audit history, subprocess spawn, atomic claim + schedule dedup, ClaimSlaPolicy SPI | C1–C15 | ✅ Complete |
| Enterprise Capabilities | Confidence-gated routing, worker selection strategy, module separation, semantic AI + LLM assist, MongoDB persistence, issue tracker, SLA reporting, multi-instance, business hours, notifications, broadcaster SPI, distributed SSE | C16–C27 | ✅ Complete |
| Lifecycle Enrichment | Named outcomes, template data schemas, conflict-of-interest exclusions, enforced builder, round-robin strategy, SlaBreachPolicy SPI + escalation removal, capability vocabulary, status lifecycle fixes + DELEGATED | C28–C35 | ✅ Complete |

```mermaid
flowchart LR
  C1["C1: Domain Baseline\nL1"] --> C2["C2: REST API\n+L2"]
  C2 --> C3["C3: Lifecycle Engine\n+L3"]
  C3 --> C4["C4: CDI Events\nL1,L3"]
  C4 --> C5["C5: Flow\n+L7"]
  C5 --> C6["C6: Ledger\n+L5"]
  C6 --> C7["C7: Label Queues\n+L4"]
  C7 --> C8["C8: Native Image"]
  C8 --> C9["C9: Form Schema\n(superseded)"]
  C9 --> C10["C10: WorkItemTemplate\nL1"]
  C10 --> C11["C11: Model Enrichment\nL1,L2"]
  C11 --> C12["C12: Audit History\nL2"]
  C12 --> C13["C13: Spawn\nL1"]
  C13 --> C14["C14: Atomic Claim\nL1"]
  C14 --> C15["C15: ClaimSlaPolicy\nL3"]

  C15 --> C16["C16: Confidence Routing\nL1,L4"]
  C16 --> C17["C17: Worker Selection\nL1"]
  C17 --> C18["C18: Module Sep\nL1"]
  C18 --> C19["C19: Semantic+LLM\nL7"]
  C19 --> C20["C20: MongoDB\nL7"]
  C20 --> C21["C21: Issue Tracker\nL7"]
  C21 --> C22["C22: SLA Reports\nL7"]
  C22 --> C23["C23: Multi-Instance\nL1,L3"]
  C23 --> C24["C24: Business Hours\nL3"]
  C24 --> C25["C25: Notifications\nL7"]
  C25 --> C26["C26: Broadcaster SPI\nL6"]
  C26 --> C27["C27: Distributed SSE\nL6"]

  C27 --> C28["C28: Named Outcomes\nL1,L2"]
  C28 --> C29["C29: Template Schemas\nL1,L2"]
  C29 --> C30["C30: CoI Exclusions\nL1"]
  C30 --> C31["C31: Builder\nL1,L2"]
  C31 --> C32["C32: Round-Robin\nL3"]
  C32 --> C33["C33: SlaBreachPolicy\nL3"]
  C33 --> C34["C34: Capability Vocab\nL1"]
  C34 --> C35["C35: Status Lifecycle\nL1,L3"]

  style C1 fill:#90EE90,color:#000
  style C2 fill:#90EE90,color:#000
  style C3 fill:#90EE90,color:#000
  style C4 fill:#90EE90,color:#000
  style C5 fill:#90EE90,color:#000
  style C6 fill:#90EE90,color:#000
  style C7 fill:#90EE90,color:#000
  style C8 fill:#90EE90,color:#000
  style C9 fill:#90EE90,color:#000
  style C10 fill:#90EE90,color:#000
  style C11 fill:#90EE90,color:#000
  style C12 fill:#90EE90,color:#000
  style C13 fill:#90EE90,color:#000
  style C14 fill:#90EE90,color:#000
  style C15 fill:#90EE90,color:#000
  style C16 fill:#90EE90,color:#000
  style C17 fill:#90EE90,color:#000
  style C18 fill:#90EE90,color:#000
  style C19 fill:#90EE90,color:#000
  style C20 fill:#90EE90,color:#000
  style C21 fill:#90EE90,color:#000
  style C22 fill:#90EE90,color:#000
  style C23 fill:#90EE90,color:#000
  style C24 fill:#90EE90,color:#000
  style C25 fill:#90EE90,color:#000
  style C26 fill:#90EE90,color:#000
  style C27 fill:#90EE90,color:#000
  style C28 fill:#90EE90,color:#000
  style C29 fill:#90EE90,color:#000
  style C30 fill:#90EE90,color:#000
  style C31 fill:#90EE90,color:#000
  style C32 fill:#90EE90,color:#000
  style C33 fill:#90EE90,color:#000
  style C34 fill:#90EE90,color:#000
  style C35 fill:#90EE90,color:#000
```

### §9.2 Chapter Index

| # | Chapter | Journey | Key issues | Status |
|---|---|---|---|---|
| C1 | Domain Baseline | Core Platform | Phase 1 | ✅ |
| C2 | REST API | Core Platform | Phase 2 | ✅ |
| C3 | Lifecycle Engine | Core Platform | Phase 3 | ✅ |
| C4 | CDI Events | Core Platform | Phase 4 | ✅ |
| C5 | Quarkus-Flow Integration | Core Platform | Phase 5, #37, #38 | ✅ |
| C6 | Ledger Module | Core Platform | Phase 6, #45, ADR-0001 | ✅ |
| C7 | Label-Based Queues | Core Platform | Phase 7, #72, ADR-0002 | ✅ |
| C8 | Native Image | Core Platform | Phase 8 | ✅ |
| C9 | Form Schema *(superseded by C29)* | Core Platform | #107, #108 | ✅ |
| C10 | WorkItemTemplate | Core Platform | #76 | ✅ |
| C11 | WorkItem Model Enrichment | Core Platform | #74, #75, #82–#89, #91 | ✅ |
| C12 | Audit History API | Core Platform | Phase 10, #109–#111 | ✅ |
| C13 | Subprocess Spawn | Core Platform | SpawnPort SPI, V17+V18 | ✅ |
| C14 | Atomic Claim + Schedule Dedup | Core Platform | #94, #96 | ✅ |
| C15 | ClaimSlaPolicy SPI | Core Platform | #125 | ✅ |
| C16 | Confidence-Gated Routing | Enterprise Capabilities | Phase 11, #112–#114 | ✅ |
| C17 | Worker Selection Strategy | Enterprise Capabilities | Phase 12, #115–#116 | ✅ |
| C18 | Module Separation | Enterprise Capabilities | #118 | ✅ |
| C19 | Semantic Skill Matching + LLM Assist | Enterprise Capabilities | #121, #124, #126, V4001 | ✅ |
| C20 | MongoDB Persistence | Enterprise Capabilities | persistence-mongodb module | ✅ |
| C21 | Issue Tracker | Enterprise Capabilities | #73, #156–#161 | ✅ |
| C22 | SLA Compliance Reporting | Enterprise Capabilities | Phase 14, #142–#145 | ✅ |
| C23 | Multi-Instance WorkItems | Enterprise Capabilities | Phase 15, #106 | ✅ |
| C24 | Business-Hours Deadlines | Enterprise Capabilities | Phase 16 | ✅ |
| C25 | Notifications | Enterprise Capabilities | Phase 17, #140–#141 | ✅ |
| C26 | Broadcaster SPI | Enterprise Capabilities | Phase 18, #147, #150 | ✅ |
| C27 | Distributed SSE + Queue Broadcaster | Enterprise Capabilities | Phase 19+20, #93, #155 | ✅ |
| C28 | Named Outcomes | Lifecycle Enrichment | Phase 21, #169, #176, #178 | ✅ |
| C29 | Template Data Schemas | Lifecycle Enrichment | Phase 22, #170 — *supersedes C9* | ✅ |
| C30 | Conflict-of-Interest Exclusions | Lifecycle Enrichment | Phase 23, #171, #186, #192, ADR-0005 | ✅ |
| C31 | WorkItemCreateRequest Builder | Lifecycle Enrichment | Phase 24, #182 | ✅ |
| C32 | Round-Robin Strategy | Lifecycle Enrichment | #117, #200, #202, #203 | ✅ |
| C33 | SlaBreachPolicy SPI + Escalation Removal | Lifecycle Enrichment | Phase 25+26, #212–#216 | ✅ |
| C34 | Capability Vocabulary | Lifecycle Enrichment | #220, ADR-0003, ADR-0004 | ✅ |
| C35 | Status Lifecycle Fixes + DELEGATED | Lifecycle Enrichment | Phase 27, #241, #243–#245 | ✅ |
| C36 | CloudEvent Adapter | Platform Integration | #273 | ✅ |

**Layer × Chapter matrix** (High/Med/Low/—):

| Layer | C1 | C2 | C3 | C4 | C5 | C6 | C7 | C8 | C9 | C10 | C11 | C12 | C13 | C14 | C15 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| L1 Domain Baseline | **H** | L | — | L | — | — | — | — | L | **M** | **M** | L | **M** | **M** | L |
| L2 REST API | — | **H** | — | — | — | — | — | — | — | — | L | **M** | — | — | — |
| L3 Lifecycle Engine | — | — | **H** | **M** | — | — | — | — | — | — | — | — | — | — | **M** |
| L4 Label System | — | — | — | — | — | — | **H** | — | — | — | — | — | — | — | — |
| L5 Ledger Integration | — | — | — | — | — | **H** | — | — | — | — | — | — | — | — | — |
| L6 Distribution | — | — | — | — | — | — | — | — | — | — | — | — | — | — | — |
| L7 Optional Modules | — | — | — | — | **M** | — | — | — | — | — | — | — | — | — | — |
| L8 Federation | — | — | — | — | — | — | — | — | — | — | — | — | — | — | — |

| Layer | C16 | C17 | C18 | C19 | C20 | C21 | C22 | C23 | C24 | C25 | C26 | C27 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| L1 Domain Baseline | L | **M** | **M** | — | — | — | — | **M** | L | — | — | — |
| L2 REST API | — | — | — | — | — | — | — | — | — | — | — | — |
| L3 Lifecycle Engine | — | — | — | — | — | — | — | **M** | **M** | — | — | — |
| L4 Label System | **M** | — | — | — | — | — | — | — | — | — | — | — |
| L5 Ledger Integration | — | — | — | — | — | — | — | — | — | — | — | — |
| L6 Distribution | — | — | — | — | — | — | — | — | — | — | **M** | **H** |
| L7 Optional Modules | — | — | **M** | **M** | **M** | **M** | **M** | — | — | **H** | — | — |
| L8 Federation | — | — | — | — | — | — | — | — | — | — | — | — |

| Layer | C28 | C29 | C30 | C31 | C32 | C33 | C34 | C35 |
|---|---|---|---|---|---|---|---|---|
| L1 Domain Baseline | **M** | **M** | **M** | L | — | L | **M** | **M** |
| L2 REST API | L | L | — | L | — | — | — | — |
| L3 Lifecycle Engine | — | — | — | — | **M** | **H** | — | **M** |
| L4 Label System | — | — | — | — | — | — | — | — |
| L5 Ledger Integration | — | — | — | — | — | — | — | — |
| L6 Distribution | — | — | — | — | — | — | — | — |
| L7 Optional Modules | — | — | — | — | — | — | — | — |
| L8 Federation | — | — | — | — | — | — | — | — |

**Sequencing rationale:**
- C1 before C2: REST requires the persisted WorkItem entity and service layer at runtime
- C2 before C3: lifecycle transitions require service methods to exist
- C3 before C4: CDI events are emitted inside service transitions; transitions must exist first
- C6 (Ledger) after C4: `LedgerEventCapture @Observes WorkItemLifecycleEvent` — the event bus must exist
- C7 (Label Queues) before C16 (Confidence Routing): C16 extends the label rule engine from C7
- C18 (Module Separation) before C19 (Semantic AI): semantic AI depends on the api/core SPI split
- C26 (Broadcaster SPI) before C27 (Distributed SSE): PostgreSQL broadcaster implements the SPI from C26
- C33 (SlaBreachPolicy SPI) before C35 (Status Lifecycle): C35 status correctness (#243 EXPIRED in isTerminal, #244 Exhausted) depends on sealed `BreachDecision` from C33

---

## §9.3 Journey 1 — Core Platform (C1–C15)

> Journey 1 delivers the WorkItem primitive from zero to a native-image-ready Quarkus extension with REST API, lifecycle engine, CDI events, optional Ledger integration, and label-based queues. All 15 chapters were completed between 2026-04-14 and 2026-04-24.

---

### Chapter C1 — Domain Baseline

**Journey:** Journey 1 — Core Platform | **Sequence:** 1 of 15 | **Status:** ✅
**Delivered:** 2026-04-14 | **Issues:** #92

**What this delivers**
Before this chapter, no human task primitive existed in the casehub-work codebase. After: the `WorkItemEntity` entity with a 10-status lifecycle, `AuditEntry` persistence, `WorkItemStore` SPI, `WorkItemService` enforcing all valid transitions, and `InMemoryWorkItemStore` in the testing module are all in place. Any Quarkus application adding `casehub-work` gets a working task inbox backed by its configured datasource.

**Accountability gaps closed**
- No structured task audit trail → `AuditEntry` appended on every status transition via `WorkItemService`

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Baseline | High |

---

### Chapter C2 — REST API

**Journey:** Journey 1 — Core Platform | **Sequence:** 2 of 15 | **Status:** ✅
**Delivered:** 2026-04-14 | **Issues:** #92

**What this delivers**
Before this chapter, the WorkItem lifecycle was accessible only via CDI injection. After: `WorkItemResource` exposes 13 endpoints with request/response DTOs and exception mappers, so any HTTP client can create, claim, complete, and delegate WorkItems via REST without touching CDI.

**Accountability gaps closed**
- No external claim surface → REST inbox with candidate group filtering on GET /workitems

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Baseline | Low |
| L2 REST API | High |

---

### Chapter C3 — Lifecycle Engine

**Journey:** Journey 1 — Core Platform | **Sequence:** 3 of 15 | **Status:** ✅
**Delivered:** 2026-04-14 | **Issues:** #92

**What this delivers**
Before this chapter, WorkItems that breached their deadline sat active indefinitely with no automated consequence. After: `ExpiryTimerJob` (@Scheduled) drives `ExpiryLifecycleService`; every item that passes its deadline either transitions to EXPIRED, returns to the candidate pool with a new deadline, or reaches ESCALATED terminal state within one scheduler cycle.

**Accountability gaps closed**
- Missed deadlines with no consequence → SLA breach fires an `AuditEntry` unconditionally on every expiry transition

**Layer Impact**
| Layer | Delta |
|---|---|
| L3 Lifecycle Engine | High |

---

### Chapter C4 — CDI Events

**Journey:** Journey 1 — Core Platform | **Sequence:** 4 of 15 | **Status:** ✅
**Delivered:** 2026-04-14 | **Issues:** #92

**What this delivers**
Before this chapter, lifecycle transitions had no observable side-channel outside `WorkItemService`. After: `WorkItemLifecycleEvent` fires on every status transition, carrying event type, WorkItem ID, actor, and optional rationale/planRef fields, so any CDI bean reacts to state changes without modifying the service.

**Accountability gaps closed**
- None (event bus infrastructure — no new accountability surface added)

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Baseline | Low |
| L3 Lifecycle Engine | Med |

---

### Chapter C5 — Quarkus-Flow Integration

**Journey:** Journey 1 — Core Platform | **Sequence:** 5 of 15 | **Status:** ✅
**Delivered:** 2026-04-14 | **Issues:** #92

**What this delivers**
Before this chapter, Quarkus-Flow workflow definitions had no native human-in-the-loop step. After: `HumanTaskFlowBridge` wraps WorkItem creation in a Quarkus-Flow `function` task returning `Uni<String>`; the `workItem()` DSL helper suspends a workflow until a human completes the assigned WorkItem.

**Accountability gaps closed**
- None (integration layer — accountability responsibility belongs to the host workflow)

**Layer Impact**
| Layer | Delta |
|---|---|
| L7 Optional Modules | Med |

---

### Chapter C6 — Ledger Module

**Journey:** Journey 1 — Core Platform | **Sequence:** 6 of 15 | **Status:** ✅
**Delivered:** 2026-04-14 | **Issues:** #92

**What this delivers**
Before this chapter, lifecycle events had no tamper-evident record and actor trust was unscored. After: the optional `casehub-work-ledger` module introduces `LedgerEventCapture`, which `@Observes WorkItemLifecycleEvent` and appends a `WorkItemLedgerEntry` joined from `casehub-ledger`'s Merkle chain; a nightly EigenTrust batch derives actor trust scores from those attestations.

**Accountability gaps closed**
- No tamper-evident audit → `WorkItemLedgerEntry` appended in Merkle hash chain on every transition
- No trust scoring → EigenTrust reputation computed nightly from audit attestations

**Layer Impact**
| Layer | Delta |
|---|---|
| L5 Ledger Integration | High |

---

### Chapter C7 — Label-Based Queues

**Journey:** Journey 1 — Core Platform | **Sequence:** 7 of 15 | **Status:** ✅
**Delivered:** 2026-04-14 | **Issues:** #92

**What this delivers**
Before this chapter, WorkItems had no label-based routing and inbox queries returned unfiltered results. After: the optional `casehub-work-queues` module delivers `LabelVocabulary` (Path-based scope hierarchy), `LabelRuleEntity` (JEXL/JQ/Lambda evaluators), `QueueView` named queries, and `WorkItemQueueEvent` CDI events; consuming apps define label vocabularies and query the inbox as named queue views.

**Accountability gaps closed**
- No label-based routing audit → `WorkItemQueueEvent` fires on every queue membership change

**Layer Impact**
| Layer | Delta |
|---|---|
| L4 Label System | High |

---

### Chapter C8 — Native Image

**Journey:** Journey 1 — Core Platform | **Sequence:** 8 of 15 | **Status:** ✅
**Delivered:** 2026-04-14 | **Issues:** #92

**What this delivers**
Before this chapter, casehub-work had no verified GraalVM native image target. After: the `@QuarkusIntegrationTest` suite validates the native binary; startup time measures 0.084 s, confirming sub-100 ms deployment for casehub-work native builds.

**Accountability gaps closed**
- None (infrastructure validation — no accountability surface)

**Layer Impact**
| Layer | Delta |
|---|---|
| Cross-cutting | — |

---

### Chapter C9 — Form Schema (superseded by C29)

**Journey:** Journey 1 — Core Platform | **Sequence:** 9 of 15 | **Status:** ✅
**Delivered:** 2026-04-15 | **Issues:** #92

**What this delivers**
`WorkItemFormSchema` was introduced as a standalone CRUD resource for JSON Schema definitions. C29 (Template Data Schemas) later moved schema ownership into `WorkItemTemplate` and deleted this entity. The net production effect of C9 is zero — the entity no longer exists in production code.

**Accountability gaps closed**
- None net (capability superseded and deleted by C29)

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Baseline | Low (net zero — entity created then deleted by C29) |

---

### Chapter C10 — WorkItemTemplate

**Journey:** Journey 1 — Core Platform | **Sequence:** 10 of 15 | **Status:** ✅
**Delivered:** 2026-04-20 | **Issues:** #92

**What this delivers**
Before this chapter, each WorkItem creation required callers to supply all fields individually. After: `WorkItemTemplate` blueprints carry title, description, candidateGroups, SLA defaults, `inputDataSchema`, `outputDataSchema` (from C29), and named outcomes (from C28); `WorkItemTemplateService.createFromTemplate()` creates WorkItems from templates with request-wins merge semantics, so consuming apps define templates once and create consistent WorkItems by name.

**Accountability gaps closed**
- None (convenience layer — no new accountability surface)

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Baseline | Med |

---

### Chapter C11 — WorkItem Model Enrichment

**Journey:** Journey 1 — Core Platform | **Sequence:** 11 of 15 | **Status:** ✅
**Delivered:** 2026-04-20 | **Issues:** #92

**What this delivers**
Before this chapter, the inbox had no notes, no relations, no live streaming, and no observability hooks. After: `WorkItemNote` (lightweight operational notes), `WorkItemRelation` graph (PART_OF and pluggable relation types), `WorkItemLink` (structured external references), SSE live event stream (GET /workitems/events), recurring cron-driven WorkItem creation, Micrometer metrics (gauges, counters, queue depth), inbox summary/clone/bulk operations, and an OpenAPI spec are all in place.

**Accountability gaps closed**
- None (enrichment — no new accountability surface)

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Baseline | Med |
| L2 REST API | Low |

---

### Chapter C12 — Audit History API

**Journey:** Journey 1 — Core Platform | **Sequence:** 12 of 15 | **Status:** ✅
**Delivered:** 2026-04-20 | **Issues:** #92

**What this delivers**
Before this chapter, audit entries were queryable only per WorkItem via its detail endpoint. After: GET /audit exposes cross-WorkItem queries with actorId, event, date, and category filters plus pagination; GET /workitems/reports/sla-breaches and GET /workitems/reports/actors/{actorId} deliver SLA compliance and actor performance summaries across the full audit history.

**Accountability gaps closed**
- No cross-item audit query → cross-item `AuditEntry` search with actorId, date, category, and event filters at GET /audit

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Baseline | Low |
| L2 REST API | Med |

---

### Chapter C13 — Subprocess Spawn

**Journey:** Journey 1 — Core Platform | **Sequence:** 13 of 15 | **Status:** ✅
**Delivered:** 2026-04-24 | **Issues:** #92

**What this delivers**
Before this chapter, parent–child WorkItem relationships had no structured representation and batch creation had no idempotency guarantee. After: `SpawnPort` SPI in `casehub-work-api` and `WorkItemSpawnService` implement idempotent batch creation; `WorkItemSpawnGroup` tracks group status and `completedCount`; the `callerRef` field on `WorkItemEntity` carries an opaque routing key (V17 migration) echoed on every lifecycle event, enabling engine restart recovery.

**Accountability gaps closed**
- None (spawn infrastructure — callerRef is a routing key, not an accountability mechanism)

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Baseline | Med |

---

### Chapter C14 — Atomic Claim + Schedule Dedup

**Journey:** Journey 1 — Core Platform | **Sequence:** 14 of 15 | **Status:** ✅
**Delivered:** 2026-04-20 | **Issues:** #92

**What this delivers**
Before this chapter, concurrent claim attempts from different cluster nodes could race and produce duplicate assignments with no detection. After: `@Version long version` on `WorkItemEntity` makes concurrent claim atomic; a second claimer receives `OptimisticLockException`, which `WorkItemResource` maps to HTTP 409 Conflict; `@Version` on `WorkItemSchedule` with `REQUIRES_NEW` prevents schedule double-fire across cluster nodes.

**Accountability gaps closed**
- Silent double-claim with no detection → `@Version` OCC produces 409 Conflict; second claimer retries transparently

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Baseline | Med |

---

### Chapter C15 — ClaimSlaPolicy SPI

**Journey:** Journey 1 — Core Platform | **Sequence:** 15 of 15 | **Status:** ✅
**Delivered:** 2026-04-23 | **Issues:** #92

**What this delivers**
Before this chapter, claim deadline breach had no configurable policy and breached items silently stayed unclaimed in the pool. After: the `ClaimSlaPolicy` SPI in `casehub-work-api` defines four pool-deadline strategies — PoolExpiry (item stays available), PoolEscalation (re-routes to new groups), PoolExtension (extends the claim deadline), and PoolFail (terminates the item); `ExpiryLifecycleService.checkClaimDeadlines()` dispatches to the configured policy, and `claimDeadline` resets on release, delegate, and expiry transitions.

**Accountability gaps closed**
- None (SPI infrastructure — policy selection is a consuming-app responsibility)

**Layer Impact**
| Layer | Delta |
|---|---|
| L3 Lifecycle Engine | Med |

---

### Chapter C16 — Confidence-Gated Routing

**Journey:** Enterprise Capabilities | **Sequence:** 1 of 12 | **Status:** ✅
**Delivered:** 2026-04-21 | **Issues:** Phase 11, #112–#114, Epic #100

**What this delivers**
Before this chapter, all WorkItems routed to the same candidate pool regardless of AI confidence metadata carried on the item. After: consuming apps register JEXL-based label rules against the `LabelRuleEngine`; rules that match on `confidenceScore` override candidate groups or priority, rerouting low-confidence items to a human review pool automatically.

**Accountability gaps closed**
- No AI-driven routing differentiation → `LabelRuleEngine` applies JEXL conditions to override routing on confidence threshold breach

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Baseline | Low |
| L4 Assignment + Routing | Med |

---

### Chapter C17 — Worker Selection Strategy

**Journey:** Enterprise Capabilities | **Sequence:** 2 of 12 | **Status:** ✅
**Delivered:** 2026-04-21 | **Issues:** Phase 12, #115–#116, Epics #100, #102

**What this delivers**
Before this chapter, assignment was implicit pool-based claiming only — no pre-assignment on creation or rerouting on delegation. After: deploying applications plug in a `WorkerSelectionStrategy` SPI; `WorkBroker` invokes the strategy on creation, release, delegation, and SLA escalation; `LeastLoadedStrategy` and `ClaimFirstStrategy` ship built-in, with `ClaimFirstStrategy` declared `@Alternative @Priority(0)` to yield to `SemanticWorkerSelectionStrategy` when present.

**Accountability gaps closed**
- None — capability addition

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Baseline | Med |

---

### Chapter C18 — Module Separation

**Journey:** Enterprise Capabilities | **Sequence:** 3 of 12 | **Status:** ✅
**Delivered:** 2026-04-22 | **Issues:** #118

**What this delivers**
Before this chapter, all SPI contracts and implementations resided in the runtime module; `casehub-engine` would pull in JPA entities, REST resources, and Flyway migrations it did not need. After: `casehub-work-api` (pure-Java SPI contracts, no Quarkus) and `casehub-work-core` (Jandex library with generic `WorkBroker` and strategies) are extracted; `casehub-engine` takes a compile dependency on `casehub-work-core` only, getting routing logic without a datasource requirement.

**Accountability gaps closed**
- None — infrastructure separation

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Baseline | Med |

---

### Chapter C19 — Semantic Skill Matching + LLM Assist

**Journey:** Enterprise Capabilities | **Sequence:** 4 of 12 | **Status:** ✅
**Delivered:** 2026-04-23 | **Issues:** #121, #124, #126, V4001, Epic #100

**What this delivers**
Before this chapter, worker assignment used load and availability only; escalated items had no diagnostic context beyond the audit log. After: `SemanticWorkerSelectionStrategy` (`@Alternative @Priority(1)`) activates when `casehub-work-ai` is on the classpath and uses embedding-based `EmbeddingSkillMatcher` to match workers to task requirements; `GET /workitems/{id}/resolution-suggestion` generates an LLM completion suggestion from audit history; `EscalationSummaryObserver` fires on `EXPIRED`/`CLAIM_EXPIRED` and writes an LLM-generated briefing to the `escalation_summary` table (V4001).

**Accountability gaps closed**
- None — capability addition

**Layer Impact**
| Layer | Delta |
|---|---|
| L7 Optional Integrations | Med |

---

### Chapter C20 — MongoDB Persistence

**Journey:** Enterprise Capabilities | **Sequence:** 5 of 12 | **Status:** ✅
**Delivered:** 2026-04-18 | **Issues:** persistence-mongodb module

**What this delivers**
Before this chapter, `casehub-work` required a relational datasource. After: the `casehub-work-persistence-mongodb` optional module provides `MongoWorkItemStore`, which displaces `JpaWorkItemStore` by classpath presence (`@Alternative @Priority(1)`); `candidateGroups` and `candidateUsers` store as MongoDB arrays; `WorkItemQuery` translates to MongoDB `Document` filters with `$regex` for label patterns.

**Accountability gaps closed**
- None — infrastructure addition

**Layer Impact**
| Layer | Delta |
|---|---|
| L7 Optional Integrations | Med |

---

### Chapter C21 — Issue Tracker Integration

**Journey:** Enterprise Capabilities | **Sequence:** 6 of 12 | **Status:** ✅
**Delivered:** 2026-04-19 + 2026-05-04 | **Issues:** #73, #156, #157, #160, #161

**What this delivers**
Before this chapter, WorkItems and external issue trackers had no bidirectional link; closing a GitHub issue had no effect on its associated WorkItem. After: the `casehub-work-issue-tracker` module provides `IssueTrackerProvider` and `IssueLinkStore` SPIs; GitHub and Jira webhook parsers translate inbound events to `NormativeResolution` (`DONE`/`DECLINE`/`FAILURE`), which drives WorkItem transitions; lifecycle events sync WorkItem status changes back to issue labels and state (V5000, V5001).

**Accountability gaps closed**
- None — capability addition

**Layer Impact**
| Layer | Delta |
|---|---|
| L7 Optional Integrations | Med |

---

### Chapter C22 — SLA Compliance Reporting

**Journey:** Enterprise Capabilities | **Sequence:** 7 of 12 | **Status:** ✅
**Delivered:** 2026-04-28 | **Issues:** Phase 14, #142–#145, Epic #104

**What this delivers**
Before this chapter, SLA compliance required custom HQL queries against the audit table. After: the `casehub-work-reports` module exposes four endpoints — `GET /workitems/reports/sla-breaches`, `/actors`, `/throughput`, and `/queue-health`; HQL `date_trunc` handles time bucketing; Caffeine caches results for five minutes; 73 tests validate results against a PostgreSQL Testcontainer dialect.

**Accountability gaps closed**
- None — reporting infrastructure addition

**Layer Impact**
| Layer | Delta |
|---|---|
| L7 Optional Integrations | Med |

---

### Chapter C23 — Multi-Instance WorkItems

**Journey:** Enterprise Capabilities | **Sequence:** 8 of 12 | **Status:** ✅
**Delivered:** 2026-04-28–29 | **Issues:** Phase 15, #106, Epic #106

**What this delivers**
Before this chapter, parallel human review required callers to manually spawn and track child WorkItems and evaluate threshold completion themselves. After: declaring `instanceCount=N` on a `WorkItemTemplate` triggers auto-spawn of N child WorkItems; `MultiInstanceCoordinator` observes completions via `@Observes` with OCC counter; `MultiInstanceGroupPolicy` evaluates the M-of-N threshold and fires `WorkItemGroupLifecycleEvent`; `InstanceAssignmentStrategy` SPI supports Pool, Explicit, RoundRobin, and Composite modes (V20 parentId, V21 group OCC columns).

**Accountability gaps closed**
- None — capability addition

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Baseline | Med |
| L3 Lifecycle Engine | Med |

---

### Chapter C24 — Business-Hours Deadlines

**Journey:** Enterprise Capabilities | **Sequence:** 9 of 12 | **Status:** ✅
**Delivered:** 2026-04-26–27 | **Issues:** Phase 16, Epic #101

**What this delivers**
Before this chapter, SLA deadlines counted calendar time including nights and weekends, making declared SLAs meaningless for human actors who work business hours only. After: the `BusinessCalendar` SPI (with `DefaultBusinessCalendar` reading `casehub.work.business-hours.*` config) and `HolidayCalendar` SPI (with `ICalHolidayCalendar` loading iCal feeds) resolve `expiresAtBusinessHours` and `claimDeadlineBusinessHours` on `WorkItemCreateRequest` to absolute `Instant` values at creation time, snapping to the next business-hour boundary.

**Accountability gaps closed**
- None — infrastructure addition

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Baseline | Low |
| L3 Lifecycle Engine | Med |

---

### Chapter C25 — Notifications

**Journey:** Enterprise Capabilities | **Sequence:** 10 of 12 | **Status:** ✅
**Delivered:** 2026-04-27 | **Issues:** Phase 17, #140–#141, Epic #103

**What this delivers**
Before this chapter, WorkItem lifecycle transitions had no outbound notification path. After: the `casehub-work-notifications` optional module provides the `NotificationChannel` SPI in `casehub-work-api`; three implementations ship — HTTP webhook, Slack, and Teams; `NotificationRule` entity (V3000) configures which lifecycle events trigger which channel; deploying applications configure rules that push messages on assignment, expiry, or escalation.

**Accountability gaps closed**
- None — capability addition

**Layer Impact**
| Layer | Delta |
|---|---|
| L7 Optional Integrations | High |

---

### Chapter C26 — Broadcaster SPI

**Journey:** Enterprise Capabilities | **Sequence:** 11 of 12 | **Status:** ✅
**Delivered:** 2026-04-30 | **Issues:** Phase 18, #147, #150

**What this delivers**
Before this chapter, SSE delivery was hardcoded in the runtime module with no alternative implementation path; alternative backends required modifying core code. After: `WorkItemEventBroadcaster` and `WorkItemQueueEventBroadcaster` interfaces are extracted; `LocalWorkItemEventBroadcaster` (`@DefaultBean`) uses an in-process Mutiny `BroadcastProcessor`; alternative backends displace the default via `@Alternative @Priority(1)` with no modification to the runtime module.

**Accountability gaps closed**
- None — infrastructure extraction

**Layer Impact**
| Layer | Delta |
|---|---|
| L6 Observability + Events | Med |

---

### Chapter C27 — Distributed SSE + Queue Broadcaster

**Journey:** Enterprise Capabilities | **Sequence:** 12 of 12 | **Status:** ✅
**Delivered:** 2026-05-01 | **Issues:** Phase 19+20, #93, #155

**What this delivers**
Before this chapter, SSE events reached only clients connected to the originating node; multi-node deployments on a shared datasource dropped events for clients on other nodes. After: `casehub-work-postgres-broadcaster` provides `PostgresWorkItemEventBroadcaster` (`@Alternative @Priority(1)`), which publishes via PostgreSQL `NOTIFY` on `casehub_work_events` and re-broadcasts incoming `LISTEN` notifications to local SSE clients, firing only on `TransactionPhase.AFTER_SUCCESS`; `casehub-work-queues-postgres-broadcaster` applies the same pattern for `casehub_work_queue_events`; neither module requires Flyway migrations.

**Accountability gaps closed**
- None — infrastructure addition

**Layer Impact**
| Layer | Delta |
|---|---|
| L6 Observability + Events | High |

---

### Chapter C28 — Named Outcomes

**Journey:** Lifecycle Enrichment | **Sequence:** 1 of 8 | **Status:** ✅
**Delivered:** 2026-05-17 | **Issues:** Phase 21, #169, #176, #178

**What this delivers**
Before this chapter, completion intent lived only in `WorkItem.resolution` JSON, requiring consumers to parse domain-specific fields to route on outcome. After: `WorkItemTemplate.outcomes` declares valid completion classifications (e.g. `approved`, `rejected`, `escalated`); `WorkItem.permittedOutcomes` snapshots that list at instantiation; `complete()` validates the supplied outcome against permitted values; `WorkItemLifecycleEvent.outcome` carries the named value for engine and Qhorus adapter routing; `reject()` accepts a named outcome (V22); `GET /workitems` inbox accepts `?outcome=` filter.

**Accountability gaps closed**
- Completion intent buried in resolution JSON → named outcome on `WorkItemLifecycleEvent` enables downstream routing without parsing

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Model | Med |
| L2 Application Services | Low |

---

### Chapter C29 — Template Data Schemas

**Journey:** Lifecycle Enrichment | **Sequence:** 2 of 8 | **Status:** ✅
**Delivered:** 2026-05-17 | **Issues:** Phase 22, #170

**What this delivers**
Before this chapter, JSON Schema lived in a standalone `WorkItemFormSchema` entity decoupled from the `WorkItemEntity` — resolution validation required an external lookup. After: `WorkItemTemplate.inputDataSchema` and `outputDataSchema` (JSON Schema draft-07 TEXT) are validated at instantiation and completion by `FormSchemaValidationService`; both schemas snapshot onto `WorkItemEntity` at creation so each item governs its own validation without external lookup. `WorkItemFormSchema` entity and its CRUD REST resource are deleted; schema discovery migrates to `GET /workitem-templates/{id}`. Migrations: V23 (schema columns on template), V24 (snapshot columns on `work_item`), V25 (drop `work_item_form_schema` table). This chapter supersedes C9.

**Accountability gaps closed**
- None — consolidation and correctness fix

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Model | Med |
| L2 Application Services | Low |

---

### Chapter C30 — Conflict-of-Interest Exclusions

**Journey:** Lifecycle Enrichment | **Sequence:** 3 of 8 | **Status:** ✅
**Delivered:** 2026-05-18 (extended through 2026-05-31) | **Issues:** Phase 23, #171, #186, #192, #220, ADR-0005

**What this delivers**
Before this chapter, excluded actors could claim or be assigned to WorkItems they had a conflict of interest in — no enforcement existed at any assignment path. After: `ExclusionPolicy` SPI in `casehub-work-api` exposes `check(userId, excludedUsers, excludedGroups)` returning `PolicyDecision` with a denial reason; `CommaSeparatedExclusionPolicy` ships as `@DefaultBean`; `excludedUsers` (TEXT CSV) on `WorkItemTemplate` and `WorkItemEntity` carries direct exclusions; `excludedGroups` (TEXT CSV) on template is expanded to actor IDs via `GroupMembershipProvider` at instantiation (V33, ADR-0005); enforcement covers all five assignment paths: claim, create, assign, delegate, and auto-assignment. `BlockedAttemptAuditService` writes `CLAIM_DENIED`, `DELEGATE_DENIED`, `CREATE_DENIED` via `@Transactional(REQUIRES_NEW)` so the audit entry commits even when the outer transaction rolls back; V32 drops the FK to allow orphaned audit entries.

**Accountability gaps closed**
- No conflict-of-interest audit trail → `CLAIM_DENIED`/`DELEGATE_DENIED`/`CREATE_DENIED` written independently of the rejected transaction

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Model | Med |

---

### Chapter C31 — WorkItemCreateRequest Builder

**Journey:** Lifecycle Enrichment | **Sequence:** 4 of 8 | **Status:** ✅
**Delivered:** 2026-05-20 | **Issues:** Phase 24, #182

**What this delivers**
Before this chapter, callers constructed `WorkItemCreateRequest` positionally with 24 parameters — adding a field silently shifted all downstream call sites. After: `WorkItemCreateRequest` is a final class with an enforced builder; the private constructor is reachable only via `Builder.build()`, making positional construction structurally impossible. 60+ call sites migrated across 7 modules. A drift-protection test (`builderHasSetterForEveryField`) guards against future field/setter mismatches.

**Accountability gaps closed**
- None — API safety and maintainability

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Model | Low |
| L2 Application Services | Low |

---

### Chapter C32 — Round-Robin Strategy

**Journey:** Lifecycle Enrichment | **Sequence:** 5 of 8 | **Status:** ✅
**Delivered:** 2026-05-21 | **Issues:** #117, #200, #202, #203, #204

**What this delivers**
Before this chapter, sequential worker rotation required consumers to implement their own cursor. After: `RoundRobinStrategy` provides cursor-backed sequential worker selection; the `routing_cursor` table (V29, PK = `pool_hash`) persists cursor state; `RoutingCursorStore` SPI is backed by `JpaRoutingCursorStore` with optimistic concurrency control; `@DefaultBean NoOpRoutingCursorStore` in `casehub-work-core` keeps the SPI optional. V30 adds `last_accessed` and `RoutingCursorCleanupJob` for TTL-based cursor GC (configurable cron and `ttl-days`). Inbox filter params (`status`, `priority`, `category`, `followUp`, `outcome`) previously declared but silently ignored are wired in this chapter (#200).

**Accountability gaps closed**
- None — new routing capability

**Layer Impact**
| Layer | Delta |
|---|---|
| L3 Routing + Assignment | Med |

---

### Chapter C33 — SlaBreachPolicy SPI + Escalation Removal

**Journey:** Lifecycle Enrichment | **Sequence:** 6 of 8 | **Status:** ✅
**Delivered:** 2026-05-22 | **Issues:** Phase 25+26, #212, #213, #215, #216

**What this delivers**
Before this chapter, escalation was governed by four separate `EscalationPolicy` implementations with no chaining. After: `SlaBreachPolicy` SPI replaces the removed `EscalationPolicy`; `onBreach(SlaBreachContext)` returns a sealed `BreachDecision` — `Fail`, `EscalateTo(groups, deadline)`, `Extend(by)`, `Chained`, or `Exhausted`; `SlaBreachContext` carries `BreachType`, `BreachedTask`, `Path` scope, and `Preferences`. `ExpiryLifecycleService` is rewritten: policy-driven decision execution; `SlaBreachEvent` CDI fires at leaf decision; `CLAIM_EXPIRED` lifecycle event fires unconditionally. `WorkItemService.extend()` (`PUT /workitems/{id}/extend`) implements the `Extend` decision. V31 adds `scope` field on `work_item` and `work_item_template`. All four `EscalationPolicy` implementations and their CDI producers are removed; `AssignmentTrigger.SLA_ESCALATED` added — after `EscalateTo`, `WorkItemAssignmentService` pre-assigns before `put()`.

**Accountability gaps closed**
- No configurable escalation chain → `Chained` `BreachDecision` with fallback; `Exhausted` final state visible in `WorkItemStatus.ESCALATED`

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Model | Low |
| L3 Routing + Assignment | High |

---

### Chapter C34 — Capability Vocabulary

**Journey:** Lifecycle Enrichment | **Sequence:** 7 of 8 | **Status:** ✅
**Delivered:** 2026-05-29 | **Issues:** #220, ADR-0003, ADR-0004

**What this delivers**
Before this chapter, capability tags were unvalidated strings — a spelling error silently produced empty candidate pools with no diagnostic. After: `Capability` is a kebab-case-enforced value type with cached regex; `CapabilityRegistry` SPI and `PermissiveCapabilityRegistry` `@DefaultBean` govern vocabulary; `CapabilityValidator` operates in three modes per `ValidationMode` enum (ADR-0004): `STRICT` rejects unknown capabilities at creation time, `WARN` logs, `PERMISSIVE` accepts all; `CapabilityParser` provides strict and lenient parsing modes. `WorkerCandidate` and `SelectionContext` are updated to use `Set<Capability>`; `WorkBroker` drops string parsing. Exception mappers cover `MalformedCapabilityException` and `UnknownCapabilityException`.

**Accountability gaps closed**
- Misconfigured capability silently empties candidate pool → `CapabilityValidator` enforces format and optionally vocabulary at WorkItem creation

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Model | Med |

---

### Chapter C35 — Status Lifecycle Fixes + DELEGATED

**Journey:** Lifecycle Enrichment | **Sequence:** 8 of 8 | **Status:** ✅
**Delivered:** 2026-06-03 | **Issues:** Phase 27, #241, #243, #244, #245

**What this delivers**
Four targeted fixes delivered together. #243: `EXPIRED` added to `WorkItemStatus.isTerminal()` — items in `EXPIRED` state were neither active nor terminal, creating a limbo in metrics and queries. #244: `BreachDecision.Exhausted` added as the fifth sealed variant — the `Chained` handler catches double-failure and calls `executeExhausted()`, setting `WorkItemStatus.ESCALATED` (terminal); `checkExpired`/`checkClaimDeadlines` catch `BreachExecutionFailed` at item level and write `BREACH_POLICY_MISCONFIGURED` audit via the isolated transaction pattern. #245: `DelegationState` enum dropped; `WorkItemStatus.DELEGATED` is set unconditionally in `delegate()` (was never set before); new `acceptDelegation()` transitions to `ASSIGNED`; new `declineDelegation()` resolves `POOL` vs `DELEGATOR` target via instance field or scope preference `DeclineTarget.KEY`; `AssignmentTrigger.DELEGATION_DECLINED` added; `DELEGATED` added to `isActive()` and `WorkItemQuery.expired()`; `PUT /workitems/{id}/accept-delegation` and `PUT /workitems/{id}/decline-delegation` REST endpoints wired. #241: `WorkItemService.findById()` routed through service layer; `GET /workitems/{id}` uses the service method. V34: drops `delegation_state` column, adds `delegation_decline_target`.

**Accountability gaps closed**
- `EXPIRED` items counted as active in metrics → `EXPIRED` now in `isTerminal()`
- Exhausted SLA breach chains had no terminal resolution → `ESCALATED` set on `Exhausted`; `BREACH_POLICY_MISCONFIGURED` audit written

**Layer Impact**
| Layer | Delta |
|---|---|
| L1 Domain Model | Med |
| L3 Routing + Assignment | Med |

---

### Chapter C36 — CloudEvent Adapter

**Journey:** Platform Integration | **Sequence:** 1 of 1 | **Status:** ✅
**Delivered:** 2026-06-23 | **Issues:** #273, #172, #299

**What this delivers**
Before this chapter, casehub-work lifecycle events were only observable by CDI beans that directly depend on `casehub-work` types. After: `WorkCloudEventAdapter` bridges `WorkItemLifecycleEvent` (24 CloudEvent types) and `WorkItemGroupLifecycleEvent` (3 CloudEvent types) to the platform `Event<CloudEvent>` bus, enabling casehub-ras and any future observer to react to WorkItem lifecycle transitions without coupling to casehub-work types.

Additionally, `WorkItemLifecycleEmitter` and `WorkItemGroupLifecycleEmitter` structurally enforce dual-channel CDI firing (`fire()` + `fireAsync()`) at all lifecycle event sites, fixing a gap where `QueueDashboard` (`@ObservesAsync`) missed 13 non-terminal event types that were previously fired sync-only.

**Inbound paths** (#172, #299): `WorkCloudEventInboundAdapter` handles two inbound CloudEvent types. `io.casehub.work.workitem.requested` (#172) creates WorkItems via template — `templateid` extension required, `ce.getId()` is callerRef. `io.casehub.work.workitem.create` (#299) creates WorkItems with full `WorkItemCreateRequest` control — no template required, data payload maps all request fields, `callerRef` from data (fallback to `ce.getId()`). Both types establish tenant context via `TenantContextRunner` and enforce idempotency via callerRef + partial unique index. The `CREATE` type enables distributed HumanTask patterns where the engine and work service run in separate Quarkus applications.

**Accountability gaps closed**
- `QueueDashboard` (`@ObservesAsync`) received only terminal events → all 24+ event types now reach async observers
- No CloudEvent bridge existed → casehub-ras can observe WorkItem lifecycle without casehub-work type dependency
- No async full-request WorkItem creation path existed → external services had to use synchronous REST API

**Layer Impact**
| Layer | Delta |
|---|---|
| L3 Lifecycle Engine | Med |
| L7 Optional Integrations | Med |

---

## §9.4 Layer Entries

### Layer — L1 Domain Baseline

**Participates in chapters:** C1, C2, C4, C9, C10, C11, C12, C13, C14, C15, C17, C18, C23, C24, C28, C29, C30, C31, C34, C35
**Architectural patterns:** Hexagonal (Ports and Adapters), Strategy, Registry
**Key protocols:** flyway-version-range-allocation *(not yet written)*, module-tier-structure *(not yet written)*, PP-20260525-607b33
**Issues:** Phase 1, #182, #220, ADR-0003, ADR-0004, ADR-0005
**Navigation:** `git log --grep="Phase 1\|#182\|#220" --oneline`
**Completed:** 2026-04-14 (baseline); enriched through 2026-06-03

#### What it adds

**Before:** No human task primitive exists in the application.
**After:** `casehub-work` (Quarkus extension) provides the WorkItem inbox — `JpaWorkItemStore @ApplicationScoped` persists items; `WorkItemService` enforces all status transitions.

What this layer adds:
- **WorkItem entity** — JPA entity with 10-status lifecycle, 30+ fields, `@Version` for OCC, `@PrePersist` UUID generation; all status transitions enforced by `WorkItemService`
- **Storage SPI** — `WorkItemStore` interface allows `@Alternative @Priority(1)` backends (MongoDB, in-memory) to displace the JPA default without touching service code
- **Audit trail** — `AuditEntry` append-only log records every transition with actor, event, and optional detail JSON
- **Capability vocabulary** — `Capability` value type (kebab-case, cached regex); `CapabilityValidator` STRICT/WARN/PERMISSIVE modes; `CapabilityRegistry` SPI

Not closed here: REST surface (L2), lifecycle scheduling (L3), label routing (L4), tamper-evidence (L5).

#### Accountability Gaps Closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| No structured task audit trail | Transitions have no observable record | `AuditEntry` appended on every `WorkItemService` call |
| Capability misconfiguration silently empties pool | Assignment routes to zero candidates | `CapabilityValidator` rejects or warns at creation time |

#### Key Files

`runtime/src/main/java/io/casehub/work/runtime/model/WorkItemEntity.java` — JPA entity (renamed from `WorkItem`); holds lifecycle state, assignment, priority, expiry, and versioning fields. The SPI boundary type is `api/src/main/java/io/casehub/work/api/WorkItem.java` (immutable record with builder).

`runtime/src/main/java/io/casehub/work/runtime/model/WorkItemStatus.java` — enum of all 10 lifecycle statuses; provides `isTerminal()` and `isActive()` predicates used by query filters and scheduler guards.

`runtime/src/main/java/io/casehub/work/runtime/model/AuditEntry.java` — append-only JPA entity recording each lifecycle event with actor, event type, timestamp, and optional detail JSON; never updated after insert.

`api/src/main/java/io/casehub/work/api/spi/WorkItemStore.java` — storage SPI interface defining `put`, `get`, `scan(WorkItemQuery)`, and default methods; no JPA types leak through the interface.

`runtime/src/main/java/io/casehub/work/runtime/repository/jpa/JpaWorkItemStore.java` — `@ApplicationScoped` Panache-backed implementation of `WorkItemStore`; translates `WorkItemQuery` to JPQL and handles indexed callerRef lookup.

`runtime/src/main/java/io/casehub/work/runtime/service/WorkItemService.java` — `@ApplicationScoped` application service that owns all status transition logic and calls `WorkItemStore` and audit services; no transition logic lives in the entity or REST layer.

`runtime/src/main/java/io/casehub/work/runtime/service/OutcomeValidator.java` — `@ApplicationScoped` bean that encapsulates named-outcome validation and JEXL condition evaluation at completion/rejection time; injected by `WorkItemService`, keeping lifecycle transition logic separate from constraint evaluation.

`api/src/main/java/io/casehub/work/api/Outcome.java` — 3-field record `(name, displayName, condition)` where `condition` is a nullable JEXL expression; snapshotted onto `WorkItem.permittedOutcomes` at instantiation as full objects, not just name strings.

`api/src/main/java/io/casehub/work/api/WorkItemQuery.java` — composable value object carrying scan criteria; assignment fields combine with OR, all other fields combine with AND; backends translate to their native query language.

`api/src/main/java/io/casehub/work/api/Capability.java` — record type enforcing kebab-case format at construction; throws `MalformedCapabilityException` on violation so format errors fail fast at system boundaries.

`persistence-memory/src/main/java/io/casehub/work/memory/InMemoryWorkItemStore.java` — `@Alternative @Priority(100)` (Tier 3) thread-safe in-memory implementation of `WorkItemStore` for tests and ephemeral deployment; requires no datasource or Quarkus boot.

#### Key Wiring

**`@PrePersist` UUID generation.** `WorkItem.id` is set in `@PrePersist` — not at construction time. This allows `BlockedAttemptAuditService` to pre-generate an ID before the exclusion check, recording `CREATE_DENIED` at a known ID even when the WorkItem never reaches the `work_item` table.

**`@Version long version` on `WorkItemEntity`.** Provides optimistic concurrency control — concurrent claim from two nodes produces `OptimisticLockException`, mapped to HTTP 409 Conflict by `WorkItemResource`. The second claimer must retry; no explicit locking is used.

**Flyway at `classpath:db/work/migration/`.** All runtime module migrations (V1–V36) live at this path per PP-20260525-607b33. Consumers must set `quarkus.flyway.locations=classpath:db/work/migration` explicitly — Quarkus has no auto-registration mechanism for extension migrations.

**`InMemoryWorkItemStore` for unit tests.** The testing module provides `InMemoryWorkItemStore` annotated `@Alternative @Priority(1)` — unit tests use this without Quarkus or a datasource; `@QuarkusTest` integration tests use `JpaWorkItemStore` with H2.

#### Architectural Decisions

**Why Storage SPI rather than direct JPA dependency in services:** Keeping `WorkItemStore` as an interface in the service layer allows alternative backends (MongoDB, Redis) to displace the JPA default via `@Alternative @Priority(1)` without modifying `WorkItemService`. Tradeoff: every new query method must be added to the SPI interface, which requires updating alternative implementations.

**Why Capability as a value type rather than validated String:** Capability (ADR-0003) enforces kebab-case format at construction time; a plain String accepts any value including misspellings that silently produce empty candidate pools. Tradeoff: callers must parse Strings to Capability at system boundaries (REST layer, config); existing String-typed fields required migration.

**Why group membership snapshot at WorkItem creation (ADR-0005):** `excludedGroups` on `WorkItemTemplate` is expanded to actor IDs at instantiation time via `GroupMembershipProvider`. Late resolution would produce non-deterministic exclusion when group membership changes mid-lifecycle. Tradeoff: the snapshot reflects membership at creation time; changes to group membership after creation do not affect the item's exclusion list.

**Why conditions are evaluated at completion time, not at instantiation (ADR-0006):** `Outcome.condition` JEXL expressions can reference completion-time context (`resolution`, `actorId`, `reason`) that is not known at instantiation. Evaluating at instantiation to pre-filter `permittedOutcomes` would lose all runtime context. The full `List<Outcome>` (with conditions) is snapshotted at instantiation — consistent with schema snapshotting — and evaluated per-outcome at `complete()` / `reject()` time by `OutcomeValidator`.

**Why JEXL as the condition expression language (ADR-0007):** `runtime.filter.JexlConditionEvaluator` was already present and used for label rules, with an established `silent(true).strict(false)` profile and the same `workItem.*` context map. Reusing it means zero new Maven dependencies and one expression syntax for template authors. Tradeoff: `silent(true)` swallows both false conditions and broken expressions identically — syntax validation at template creation time is deferred.

#### Pattern Introduced

`@DefaultBean` CDI displacement — a no-op or JPA `@DefaultBean` is displaced by `@Alternative @Priority(1)` via classpath presence; no consumer config change required.

#### Pattern Anchor

`runtime/src/main/java/io/casehub/work/runtime/repository/jpa/JpaWorkItemStore.java` — `@ApplicationScoped` JPA default
`persistence-memory/src/main/java/io/casehub/work/memory/InMemoryWorkItemStore.java` — `@Alternative @Priority(100)` (Tier 3) ephemeral override

#### Gotchas

**Symptom:** `WorkItemQuery.expired()` does not include items in a newly added non-terminal status; `ExpiryTimerJob` skips them silently.
**Cause:** `WorkItemQuery.expired()` and `WorkItemQuery.claimExpired()` use explicit status sets. A new active status absent from those sets is invisible to the scheduler.
**Fix:** When adding a new non-terminal `WorkItemStatus` value, update three places atomically: `WorkItemStatus.isActive()`, `WorkItemStatus.isTerminal()`, and the status predicates inside `WorkItemQuery.expired()` and `WorkItemQuery.claimExpired()`.

**Symptom:** `CREATE_DENIED` audit entry is absent after an exclusion check rejects a WorkItem creation.
**Cause:** `BlockedAttemptAuditService` writes via `@Transactional(REQUIRES_NEW)`, committing independently. If the audit bean is missing from CDI or the REQUIRES_NEW transaction fails, the entry is silently dropped.
**Fix:** Verify `BlockedAttemptAuditService` appears in the CDI graph (`@ApplicationScoped` with no `@Vetoed`); confirm V32 migration dropped `fk_audit_entry_work_item` — without it, `CREATE_DENIED` records fail FK validation.

#### Pattern to Replicate

1. Define a domain entity with UUID PK, `@Version` for OCC, `@PrePersist` for timestamp and ID generation, and a status enum with `isTerminal()` / `isActive()` switch methods.
2. Define a `Store` interface with `put`, `get`, and `scan(Query)` methods — no JPA types in the interface.
3. Implement the `Store` with `@ApplicationScoped` JPA default; add an `@Alternative @Priority(1)` in-memory implementation for zero-datasource unit tests.
4. Define a `Service` class that takes the `Store` as a CDI dependency; enforce all status transitions here — never in the entity or REST layer.
5. Define an append-only `AuditEntryStore` with a separate JPA entity; call it inside every `Service` state transition.
6. Write Flyway migrations at `classpath:db/<reponame>/migration/` (not `db/migration/`) and document the version range in the module's README.
7. Configure `@QuarkusTest` integration tests with H2 `MODE=PostgreSQL`; use the in-memory store for unit tests that need no Quarkus boot.

### Layer — L2 REST API

**Participates in chapters:** C2, C11, C12, C28, C29, C31
**Architectural patterns:** Thin REST layer — no business logic in resources
**Key protocols:** auth-retrofit-readiness *(not yet written)*
**Issues:** Phase 2, #83–#88, #182
**Navigation:** `git log --grep="Phase 2\|#83\|#182" --oneline`
**Completed:** 2026-04-14 (baseline); extended through 2026-05-20

#### What it adds

**Before:** WorkItem lifecycle accessible only via CDI injection inside the same application.
**After:** `WorkItemResource @Path("/workitems")` exposes the full inbox lifecycle over HTTP; any external client can create, claim, complete, delegate, and query WorkItems.

What this layer adds:
- **REST resources** — 7 JAX-RS resource classes under `/workitems`; each sub-resource delegates to the service layer with no business logic in the resource method
- **PATCH /workitem-templates/{id}** — JSON Merge Patch (RFC 7396) partial update; absent fields unchanged, null clears; handles 25 fields across String/Integer/Boolean type patterns with special deserialization for `name`, `priority` (enum), `outcomes` (`List<Outcome>`), and JSON Schema fields
- **Enforced builder** — `WorkItemCreateRequest` final class with private constructor reachable only via `Builder.build()`; positional construction is structurally impossible
- **Exception mappers** — `OptimisticLockException` → HTTP 409 Conflict; `MalformedCapabilityException` → HTTP 400; `UnknownCapabilityException` → HTTP 400

Not closed here: SLA breach (L3), label routing (L4), ledger recording (L5).

#### Accountability Gaps Closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| No external claim surface | Only in-process CDI callers can interact with WorkItems | REST inbox with `?assignee` + `?candidateGroups` query params |

#### Key Files

- `runtime/src/main/java/io/casehub/work/runtime/api/WorkItemResource.java` — root JAX-RS resource at `@Path("/workitems")`; inbox, summary, and per-item lifecycle endpoints
- `runtime/src/main/java/io/casehub/work/runtime/api/WorkItemBulkResource.java` — bulk lifecycle operations at `@Path("/workitems/bulk")`; claim and complete multiple items in one request
- `runtime/src/main/java/io/casehub/work/runtime/api/WorkItemSpawnResource.java` — spawn and spawn-group sub-resource under `/workitems/{id}/spawn`
- `runtime/src/main/java/io/casehub/work/runtime/api/WorkItemInstancesResource.java` — multi-instance child listing at `/workitems/{id}/instances`
- `runtime/src/main/java/io/casehub/work/runtime/api/WorkItemRelationResource.java` — inter-item relation management at `@Path("/workitems/{id}/relations")`
- `runtime/src/main/java/io/casehub/work/runtime/api/WorkItemScheduleResource.java` — schedule sub-resource at `@Path("/workitem-schedules")`
- `runtime/src/main/java/io/casehub/work/runtime/api/WorkItemTemplateResource.java` — template CRUD at `@Path("/workitem-templates")`
- `runtime/src/main/java/io/casehub/work/runtime/api/AuditResource.java` — audit trail query endpoint at `@Path("/audit")`
- `runtime/src/main/java/io/casehub/work/runtime/api/AsyncApiResource.java` — serves the AsyncAPI spec at `@Path("/q/asyncapi")`
- `runtime/src/main/java/io/casehub/work/runtime/api/VocabularyResource.java` — vocabulary definition management at `@Path("/vocabulary")`
- `runtime/src/main/java/io/casehub/work/runtime/filter/LabelRuleResource.java` — label rule CRUD at `@Path("/label-rules")`
- `runtime/src/main/java/io/casehub/work/runtime/api/SpawnGroupResource.java` — spawn-group entity CRUD at `@Path("/spawn-groups")`
- `runtime/src/main/java/io/casehub/work/runtime/api/CreateWorkItemRequest.java` — record DTO for HTTP request body; flat string fields decoded by the resource before service call
- `runtime/src/main/java/io/casehub/work/runtime/model/WorkItemCreateRequest.java` — enforced-builder domain request object; private constructor, all required fields explicit and named
- `runtime/src/main/java/io/casehub/work/runtime/api/WorkItemResponse.java` — record DTO returned from every WorkItem read endpoint; maps domain fields to JSON-serialisable types
- `runtime/src/main/java/io/casehub/work/runtime/api/WorkItemMapper.java` — stateless utility class; converts `WorkItemEntity` domain objects to `WorkItemResponse` DTOs with no business logic

#### Key Wiring

**`@Path("/workitems")` on `WorkItemResource`.** All sub-resources use relative paths from this root. The resource carries no CDI qualifier — a single application-scoped instance serves all requests via JAX-RS injection.

**No `@RolesAllowed` on any resource method.** Per the auth-retrofit-readiness protocol, resource methods stay thin enough for auth retrofit — a consuming application adds `@RolesAllowed` at the resource level or via a CDI interceptor without modifying the extension.

**`WorkItemCreateRequest` enforced builder.** The constructor is private; the only way to construct the request is `WorkItemCreateRequest.builder().[fields].build()`. This prevents future field additions from silently shifting positional arguments at call sites. Drift-protection test `builderHasSetterForEveryField` fails at compile time if a field has no corresponding builder setter.

#### Architectural Decisions

**Why thin REST resources (no business logic in resource methods):** Business logic in resource methods makes it impossible to test service behaviour without an HTTP stack. `WorkItemService` is the testable unit; `WorkItemResource` is a translation layer only. Tradeoff: more classes, but each testable independently.

**Why enforced builder over positional record (#182):** A 24-parameter record silently shifts all downstream call sites when a field is inserted in the middle. An enforced builder makes each parameter explicit and named; insertion of a new field does not compile until every call site is updated. Tradeoff: 60+ call site migrations at the time of change.

#### Pattern Introduced

Thin REST delegation — resource method validates input, calls service, maps result to DTO; no conditional logic, no entity access, no exception handling beyond the mappers.

#### Pattern Anchor

`runtime/src/main/java/io/casehub/work/runtime/api/WorkItemResource.java` — root resource; every method delegates to `WorkItemService`

#### Gotchas

**Symptom:** `OptimisticLockException` surfaces as HTTP 500 instead of HTTP 409 when two concurrent claim requests race.
**Cause:** `OptimisticLockException` is not mapped to an HTTP status code by default in Quarkus RESTEasy.
**Fix:** Register an `ExceptionMapper<OptimisticLockException>` that returns `Response.status(Response.Status.CONFLICT).build()`. casehub-work ships this mapper in the runtime module; if you see 500s on concurrent claim, verify the mapper is on the classpath and not excluded by CDI vetoing.

**Symptom:** A call to `WorkItemCreateRequest.builder().build()` compiles but `WorkItemService.create()` throws `NullPointerException` on a required field.
**Cause:** Builder fields are set to null by default; `Builder.build()` does not validate required fields — it only assembles the object.
**Fix:** Required fields (`title`, `createdBy`) are validated inside `WorkItemService.create()` which throws `IllegalArgumentException`. Callers should set all fields before calling `build()`.

#### Pattern to Replicate

1. Define one root JAX-RS resource at a stable base path (`@Path("/workitems")`); use sub-resource classes for nested concerns (spawn, instances, notes).
2. Each resource method: validate the request DTO, call the service method, map the result to a response DTO — no conditional logic, no entity access.
3. Register `ExceptionMapper` beans for domain exceptions (`OptimisticLockException` → 409, `MalformedCapabilityException` → 400) rather than try/catch in resource methods.
4. Use an enforced builder for any request DTO with more than 5 fields — make the constructor private, expose only `builder()`.
5. Leave resource methods unannotated for auth — the consuming application adds `@RolesAllowed` at deployment time.

### Layer — L3 Lifecycle Engine

**Participates in chapters:** C3, C4, C15, C23, C24, C32, C33, C35
**Architectural patterns:** Event-Driven, Strategy, Observer
**Key protocols:** flyway-version-range-allocation *(not yet written)*
**Issues:** Phase 3, Phase 4, #125, #212, #213, #243, #244
**Navigation:** `git log --grep="Phase 3\|Phase 4\|#212\|#213" --oneline`
**Completed:** 2026-04-14 (baseline); enriched through 2026-06-03

#### What it adds

**Before:** WorkItem status transitions had no automatic time-based consequence; expired items remained active indefinitely.
**After:** `ExpiryTimerJob @Scheduled` drives `ExpiryLifecycleService`, which executes `SlaBreachPolicy.onBreach()` per expired item, transitioning items to EXPIRED, PENDING (EscalateTo), or ESCALATED (Exhausted); `WorkItemLifecycleEvent` fires on every transition.

What this layer adds:
- **Scheduler** — `ExpiryTimerJob` fires on interval; detects expired WorkItems via `WorkItemQuery`; `ClaimDeadlineTimerJob` fires on the same interval to detect claim-expired WorkItems
- **Breach policy execution** — `ExpiryLifecycleService` calls `SlaBreachPolicy.onBreach(SlaBreachContext)` per item; executes the sealed `BreachDecision`; catches `BreachExecutionFailed` at item level to avoid aborting the batch
- **Claim deadline policy** — `ClaimSlaPolicy` SPI governs what happens when a WorkItem's claim deadline passes (C15)
- **CDI event bus** — `WorkItemLifecycleEvent` fires synchronously inside every `WorkItemService` state transition; carries event type, WorkItem ID, actor, callerRef, and optional named outcome

Not closed here: tamper-evident recording (L5), distributed broadcast (L6).

#### Accountability Gaps Closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| Missed deadlines with no audit consequence | Items silently stay PENDING/ASSIGNED past SLA | `SlaBreachEvent` CDI fires on every breach; `EXPIRED`/`SLA_REASSIGNED` audit entry written unconditionally |
| SLA breach policy exhaustion left no terminal state | Chained policy with two failing branches left item stranded | `BreachDecision.Exhausted` → `WorkItemStatus.ESCALATED` terminal; `BREACH_POLICY_MISCONFIGURED` audit if policy throws |

#### Key Files

- `runtime/src/main/java/io/casehub/work/runtime/service/ExpiryTimerJob.java` — `@Scheduled` job that fires on configurable interval (`casehub.work.cleanup.expiry-check-seconds`) and delegates to `ExpiryLifecycleService.checkExpired()`
- `runtime/src/main/java/io/casehub/work/runtime/service/ClaimDeadlineTimerJob.java` — `@Scheduled` job on the same interval that delegates to `ExpiryLifecycleService.checkClaimDeadlines()` for items whose claim deadline has passed
- `runtime/src/main/java/io/casehub/work/runtime/service/ExpiryLifecycleService.java` — dispatches `SlaBreachPolicy.onBreach()` per expired item, executes the sealed `BreachDecision`, fires `SlaBreachEvent`, and catches `BreachExecutionFailed` at item level to write a diagnostic audit entry without aborting the batch
- `api/src/main/java/io/casehub/work/api/WorkItemLifecycleEvent.java` — CDI event fired synchronously after every WorkItem state transition; carries post-mutation status, actor, rationale, planRef, and outcome; embeds the WorkItem entity so observers avoid a second store lookup
- `api/src/main/java/io/casehub/work/api/SlaBreachPolicy.java` — SPI that receives a `SlaBreachContext` and returns a sealed `BreachDecision`; stateless; called by `ExpiryLifecycleService` for both completion-expiry and claim-expiry breaches
- `api/src/main/java/io/casehub/work/api/ClaimSlaPolicy.java` — SPI that computes the pool-phase deadline when a WorkItem returns to the unclaimed pool; four built-in implementations (ContinuationPolicy, FreshClockPolicy, SingleBudgetPolicy, PhaseClockPolicy) override via `@Alternative @Priority(1)`

#### Key Wiring

**`@Scheduled(every = ...)` on `ExpiryTimerJob` and `ClaimDeadlineTimerJob`.** Both jobs use `quarkus-scheduler` with a configurable interval (`casehub.work.cleanup.expiry-check-seconds`). In a clustered deployment without a cluster lock identity, multiple nodes may fire concurrently — safe because `ExpiryLifecycleService` processes each item within a `@Transactional` boundary with OCC on `WorkItem.version`.

**`WorkItemLifecycleEvent` fires synchronously via `Event.fire()`, not `fireAsync()`.** Observers that use `@ObservesAsync` will miss these events. `SlaBreachEvent` also fires synchronously. This is intentional — synchronous delivery keeps breach audit entries in the same transaction scope as the status transition.

**`SlaBreachContext` carries `Path scope` and `Preferences`.** Policy implementations resolve scope-based preferences (e.g. per-team breach escalation config) by calling `preferences.get(key, scope)`. Without `scope`, all policies share the root-scope config regardless of where the WorkItem originated.

**`BreachExecutionFailed` is caught at item level, not batch level.** If `SlaBreachPolicy.onBreach()` throws, `ExpiryLifecycleService.checkExpired()` writes a `BREACH_POLICY_MISCONFIGURED` audit entry and continues to the next item. The batch never aborts. This prevents one misconfigured policy from halting all SLA processing.

#### Architectural Decisions

**Why sealed `BreachDecision` rather than an open interface:** A sealed type is exhaustive at compile time — all five variants (Fail, EscalateTo, Extend, Chained, Exhausted) are handled in every switch. An open interface would allow implementing classes to introduce variants that the execution engine doesn't handle, silently doing nothing. Tradeoff: adding a new decision variant requires updating the execution engine.

**Why synchronous `Event.fire()` rather than `fireAsync()`:** Synchronous events keep audit side-effects (LedgerEventCapture) in the same transaction scope as the status transition. If the transaction rolls back, the audit event is also discarded. Async events would fire independently of transaction outcome, producing audit records for rolled-back transitions. Tradeoff: a slow observer blocks the transition.

#### Pattern Introduced

Item-level exception isolation — exceptions from policy execution are caught per item inside a loop; the item is skipped with an audit record; the batch continues. Never catch exceptions at batch level unless you intend to abort the entire batch.

#### Pattern Anchor

`runtime/src/main/java/io/casehub/work/runtime/service/ExpiryLifecycleService.java` — `checkExpired()` and `checkClaimDeadlines()` methods show the item-level catch pattern

#### Gotchas

**Symptom:** `SlaBreachEvent` observers fire even when `SlaBreachPolicy` is not configured — e.g. on items that expire without a policy implementation on the classpath.
**Cause:** `SlaBreachEvent` fires for all `BreachDecision` outcomes that reach the SlaBreachEvent fire call — not only Fail. The event fires regardless of whether a custom policy is installed.
**Fix:** Do not use `@Observes SlaBreachEvent` as the only signal for "item has a breach policy" — it fires on all decisions including EscalateTo and Exhausted. Use `event.decision()` to filter by the specific outcome you care about.

**Symptom:** `@ObservesAsync WorkItemLifecycleEvent` observer never fires.
**Cause:** `WorkItemLifecycleEvent` is dispatched via `Event.fire()` (synchronous), not `fireAsync()`. Async observers are not invoked by synchronous dispatch.
**Fix:** Use `@Observes WorkItemLifecycleEvent` (synchronous). If you need async behaviour, wrap the observer body in `CompletableFuture.runAsync()` or use a separate CDI event.

#### Pattern to Replicate

1. Define a scheduler job (`@Scheduled`) that queries for items past their deadline using a `WorkItemQuery` equivalent with explicit status set predicates.
2. For each item, call a configurable policy SPI that returns a sealed decision type — never hardcode the consequence inside the scheduler.
3. Execute the decision inside a `@Transactional` method; catch policy exceptions at item level and write a diagnostic audit entry; never abort the batch for a single item failure.
4. Fire a CDI event synchronously (`Event.fire()`) inside the status transition method — this keeps the event in the same transaction scope as the state change.
5. Define the policy's context object (BreachDecision equivalent) to carry scope and preferences so policy implementations can resolve per-team or per-context configuration.

### Layer — L4 Label System

**Participates in chapters:** C7, C11, C16
**Architectural patterns:** Strategy (label rule evaluators), Registry (vocabulary scopes)
**Key protocols:** PP-20260525-607b33 (Flyway path scoping), ADR-0002
**Issues:** Phase 7, #72, ADR-0002
**Navigation:** `git log --grep="Phase 7\|#72" --oneline`
**Completed:** 2026-04-16–18

#### What it adds

**Before:** WorkItems had no labelling, no vocabulary enforcement, and no named queue views.
**After:** `casehub-work-runtime` carries `LabelVocabulary` + `LabelDefinition` (path-typed scope hierarchy via `io.casehub.platform.api.path.Path`) and `WorkItemLabelEntity` (MANUAL/INFERRED persistence on WorkItem). `casehub-work-queues` adds `LabelRuleEntity` (single-pass evaluation — no cascade tracking needed), `QueueView` named label-pattern queries, `WorkItemQueueMembership` (persistent before-state for membership diffing), and `WorkItemQueueEvent` CDI events.

What this layer adds:
- **Vocabulary** — `LabelVocabulary` (runtime) + `LabelDefinition` (runtime, path typed as `io.casehub.platform.api.path.Path`, stored as VARCHAR via `PathAttributeConverter`) define valid label paths scoped by `Path` hierarchy (root = widest)
- **Label persistence** — `WorkItemLabelEntity` (runtime, `@Embeddable`) carries a `path` String, a `LabelPersistence` enum (MANUAL/INFERRED), and `appliedBy` (userId or labelRuleId)
- **Label rule engine** — `LabelRuleEntity` (queues) stores conditions (JEXL/JQ/Lambda) and platform `LabelAction` lists; unified `LabelRuleEngine` re-evaluates INFERRED labels on every WorkItem mutation using internal dispatch to `JexlConditionEvaluator`, `JqConditionEvaluator`, or `LambdaLabelEvaluator`
- **Label evaluation** — `LabelRuleEngine` (queues) uses single-pass evaluation; no cascade tracking or inverse index needed for rule deletion
- **Queue views** — `QueueView` (queues) stores a `labelPattern` String (supports `*`/`**` wildcards) that resolves to `WorkItemStore.scan()` calls at request time
- **Membership persistence** — `WorkItemQueueMembership` (queues) records the before-state of queue membership so `QueueMembershipTracker` can diff across JVM restarts without re-firing ADDED events on startup
- **Queue events** — `WorkItemQueueEvent` CDI record fires on ADDED/REMOVED/CHANGED queue membership changes; `WorkItemQueueEventBroadcaster` (SPI) fans out to SSE subscribers

Not closed here: distribution of queue events across nodes (L6).

#### Accountability Gaps Closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| No label-based routing audit | Label application and removal have no observable record | `WorkItemQueueEvent` fires on every membership change |
| Label rule deletion cleanup | Removing a rule without cleanup could leave stale labels | Single-pass evaluation in `LabelRuleEngine` — no cascade tracking needed |

#### Key Files

| File | What it does |
|---|---|
| `runtime/src/main/java/io/casehub/work/runtime/model/LabelVocabulary.java` | Scoped container for LabelDefinition entries; `scope` uses `Path` from `casehub-platform-api` with `PathAttributeConverter` |
| `runtime/src/main/java/io/casehub/work/runtime/model/LabelDefinition.java` | Declared label path within a vocabulary; `path` field uses `PathAttributeConverter` |
| `runtime/src/main/java/io/casehub/work/runtime/model/PathAttributeConverter.java` | JPA converter: `Path.value()` on write, `Path.parse()` on read; `autoApply = false` |
| `runtime/src/main/java/io/casehub/work/runtime/model/WorkItemLabelEntity.java` | `@Embeddable` (renamed from `WorkItemLabel`); carries `path` (String), `persistence` (MANUAL/INFERRED), `appliedBy`. SPI equivalent: `api/.../WorkItemLabel` record |
| `runtime/src/main/java/io/casehub/work/runtime/api/VocabularyResource.java` | REST resource for vocabulary CRUD; uses FQN `io.casehub.platform.api.path.Path` to avoid collision with `jakarta.ws.rs.Path` |
| `queues/src/main/java/io/casehub/work/queues/model/LabelRuleEntity.java` | Label rule entity; stores `conditionLanguage`, `conditionExpression`, platform `LabelAction` list (serialised JSON), `Path` scope (management metadata — not an execution predicate) |
| `queues/src/main/java/io/casehub/work/queues/service/LabelRuleEngine.java` | Single-pass label rule evaluation — no inverse index needed |
| `queues/src/main/java/io/casehub/work/queues/model/QueueView.java` | Named queue; `labelPattern` String with wildcard support; `Path` scope for management visibility |
| `queues/src/main/java/io/casehub/work/queues/model/WorkItemQueueMembership.java` | Persistent before-state; prevents re-firing ADDED events on JVM restart |
| `queues/src/main/java/io/casehub/work/queues/event/WorkItemQueueEvent.java` | CDI record event: `workItemId`, `queueViewId`, `queueName`, `QueueEventType` |
| `queues/src/main/java/io/casehub/work/queues/service/LabelRuleEngine.java` | Single-pass rule evaluation: `evaluate(WorkItem)` only |
| `queues/src/main/java/io/casehub/work/queues/service/LabelRuleEngine.java` | Internal dispatch: discovers `WorkItemExpressionEvaluator` instances via CDI Instance; dispatches by language string |
| `queues/src/main/java/io/casehub/work/queues/service/JqConditionEvaluator.java` | JQ evaluator strategy; injects `JQEvaluator` from `io.casehub.platform.expression` |
| `queues/src/main/java/io/casehub/work/queues/service/WorkItemQueueEventBroadcaster.java` | SPI: `stream(UUID queueViewId)` returns hot Mutiny Multi; default impl is `LocalWorkItemQueueEventBroadcaster` |

#### Key Wiring

**`LabelDefinition.path` uses `PathAttributeConverter`.** The `path` field is `io.casehub.platform.api.path.Path` in Java but stored as `VARCHAR(500)` in the database. The converter calls `Path.value()` on write and `Path.parse()` on read. `autoApply = false` — the converter is opt-in per field, not global. `VocabularyResource` must use the fully qualified name `io.casehub.platform.api.path.Path` in method bodies — it cannot use a simple import because `jakarta.ws.rs.Path` is already in scope from the class-level `@Path` annotation. `LabelVocabulary.scope` also uses `PathAttributeConverter` — root scope stores as empty string, non-root as the path value string.

**JQ label rule evaluation delegates to `casehub-platform-expression`.** `JqConditionEvaluator` injects `JQEvaluator` from `io.casehub.platform.expression`. If that module is absent from the classpath, CDI fails to satisfy the injection point when a JQ rule is evaluated. JEXL and Lambda rules have no external dependency and work without it.

**`LabelRuleEngine` uses single-pass evaluation.** The unified `LabelRuleEngine` evaluates label rules in a single pass — no inverse index or cascade tracking needed. Rule deletion is straightforward; INFERRED labels are re-evaluated on the next WorkItem mutation. No `cascadeDelete()` method exists.

**`WorkItemQueueMembership` prevents startup event storms.** `QueueMembershipTracker` diffs the current membership set against the `work_item_queue_membership` table. On a clean JVM start with an empty membership table, every WorkItem in every queue would emit ADDED — rebuilding the persistent table first prevents this. The table is written atomically within the same transaction as the `WorkItemLifecycleEvent` that triggered the evaluation.

#### Architectural Decisions

**Why MANUAL vs INFERRED persistence (ADR-0002):** MANUAL labels survive filter re-evaluation — a human-applied label is never removed by the filter engine. INFERRED labels are recomputed on every WorkItem mutation — they reflect the current state of the filter rules. This allows rules to evolve without retroactively changing historical MANUAL labels. Tradeoff: INFERRED labels incur filter re-evaluation cost on every mutation.

**Why Path type for LabelDefinition.path:** `Path.isAncestorOf()` enables hierarchy-aware queries ("all children of legal/contracts") without string prefix operations. A bare String would require `LIKE 'legal/contracts/%'` in JPA, which is less expressive and non-portable. Tradeoff: REST boundaries must parse String → Path and serialize Path → String explicitly; `VocabularyResource` must use the fully qualified class name to avoid the `jakarta.ws.rs.Path` collision.

**Why single-pass evaluation rather than an inverse index:** The unified `LabelRuleEngine` evaluates rules fresh on each WorkItem mutation, avoiding the complexity of tracking which rules applied which labels. No inverse index table is needed. Tradeoff: rule evaluation is performed on every mutation, but persistence write overhead is eliminated.

#### Pattern Introduced

MANUAL/INFERRED label persistence — human-applied labels survive re-evaluation; system-applied labels are recomputed on mutation. Distinguishing the persistence type at the label level rather than at the filter level keeps the semantics clear across multiple concurrent filter rules.

#### Pattern Anchor

`runtime/src/main/java/io/casehub/work/runtime/model/WorkItemLabel.java` — `persistence` field (MANUAL/INFERRED) is the discriminator

#### Gotchas

**Symptom:** JQ label rule conditions throw `NullPointerException` or `UnsatisfiedResolutionException` at runtime even though the rule entity exists in the database.
**Cause:** `JqConditionEvaluator` injects `JQEvaluator` from `io.casehub.platform.expression`. If that module is absent from the classpath, CDI cannot satisfy the injection point and the application fails to start or throws on first JQ rule evaluation.
**Fix:** Add `casehub-platform-expression` as a runtime dependency alongside `casehub-work-queues` in the consuming application's `pom.xml`. JEXL and Lambda rules work without it.

**Symptom:** `VocabularyResource` fails to compile with "reference to Path is ambiguous".
**Cause:** `VocabularyResource` has `jakarta.ws.rs.Path` in scope from the class-level `@Path("/vocabulary")` annotation; importing `io.casehub.platform.api.path.Path` creates a collision.
**Fix:** Do not import `io.casehub.platform.api.path.Path` in `VocabularyResource.java`. Use the fully qualified name `io.casehub.platform.api.path.Path` everywhere in that class where the platform Path type is needed.

**Symptom:** ADDED events fire for all current queue members on every JVM restart.
**Cause:** `QueueMembershipTracker` diffs against `WorkItemQueueMembership`. If that table is empty (e.g. after a schema wipe), every member appears new.
**Fix:** Do not wipe `work_item_queue_membership` independently of `queue_view` and `work_item_filter`. The table is an operational cache and should be treated as part of the queue subsystem's persistent state.

#### Pattern to Replicate

1. Define a vocabulary entity (`LabelVocabulary` equivalent) with a scope enum (GLOBAL → narrowest) and optional owner; define a definition entity (`LabelDefinition` equivalent) with a `Path` field stored via an `AttributeConverter<Path, String>` (`autoApply = false`).
2. Embed a label annotation type (`WorkItemLabelEntity` equivalent) as `@Embeddable` on the domain entity; carry both the path (String at storage level) and a persistence enum (MANUAL/INFERRED) so the filter engine can distinguish human from system labels.
3. Implement label rule evaluator strategies behind a common interface (`WorkItemExpressionEvaluator`); register them via CDI `Instance<>` in the rule engine; delegate JQ evaluation to an external library injected via CDI — do not bundle a JQ runtime.
4. Use single-pass evaluation for label rules; re-evaluate on entity mutation rather than maintaining an inverse index.
5. Define named view entities (`QueueView` equivalent) that store a label pattern query with wildcard support; resolve them to store scan calls at request time rather than materialising the result set.
6. Persist queue membership state (`WorkItemQueueMembership` equivalent) so membership diffs survive JVM restarts without re-firing ADDED events on startup.
7. Fire CDI events synchronously on queue membership changes for downstream observers (SSE broadcaster, metrics, audit); expose an SPI interface (`WorkItemQueueEventBroadcaster` equivalent) so alternative fan-out backends can replace the in-process default.

### Layer — L5 Ledger Integration

**Participates in chapters:** C6
**Architectural patterns:** Observer, Chain of Responsibility (hash chain), JOINED inheritance
**Key protocols:** ADR-0001, ledger-subclass-extension *(not yet written)*
**Issues:** Phase 6, #45, ADR-0001
**Navigation:** `git log --grep="Phase 6\|#45" --oneline`
**Completed:** 2026-04-14–15

#### What it adds

**Before:** WorkItem lifecycle transitions had no tamper-evident audit record; trust scoring was not possible.
**After:** `casehub-work-ledger` optional module appends a `WorkItemLedgerEntry` (JOINED from `casehub-ledger`'s `LedgerEntry`) on every lifecycle event; EigenTrust nightly batch computes actor trust scores from attestation records.

What this layer adds:
- **Immutable audit chain** — `WorkItemLedgerEntry` extends `LedgerEntry` via JPA JOINED inheritance; each entry carries a `prevHash` forming a tamper-evident Merkle chain
- **Trust scoring** — EigenTrust nightly batch reads `WorkItemLedgerEntry` attestations; trust scores feed back into `WorkerSelectionStrategy` implementations
- **Zero-core coupling** — `LedgerEventCapture @Observes WorkItemLifecycleEvent` is the sole coupling point; if the module is absent from the classpath, events fire into the void with no impact on the core

Not closed here: actor trust routing decisions (that is casehub-ledger's TrustScoreRoutingPublisher CDI).

#### Accountability Gaps Closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| No tamper-evident lifecycle record | Audit entries could be deleted or modified after the fact | Merkle hash chain in `WorkItemLedgerEntry` — any deletion breaks the chain |
| No trust scoring input | Agent and human selection has no outcome-based weighting | EigenTrust attestations in `WorkItemLedgerEntry` feed trust score computation |

#### Key Files

- `ledger/src/main/java/io/casehub/work/ledger/service/LedgerEventCapture.java` — `@ApplicationScoped` CDI observer; `onWorkItemEvent(@Observes WorkItemLifecycleEvent)` is the sole coupling point between core and ledger module
- `ledger/src/main/java/io/casehub/work/ledger/model/WorkItemLedgerEntry.java` — `@Entity` extending `LedgerEntry` via JPA JOINED inheritance; stores WorkItem-specific fields alongside the base hash-chain columns
- `ledger/src/main/java/io/casehub/work/ledger/repository/WorkItemLedgerEntryRepository.java` — SPI repository interface for ledger entry persistence
- `ledger/src/main/java/io/casehub/work/ledger/repository/jpa/JpaWorkItemLedgerEntryRepository.java` — JPA implementation of the repository
- `ledger/src/main/java/io/casehub/work/ledger/api/LedgerResource.java` — REST resource exposing ledger entry reads
- `ledger/src/main/java/io/casehub/work/ledger/api/LedgerMapper.java` — maps `WorkItemLedgerEntry` to response DTOs
- `ledger/src/main/java/io/casehub/work/ledger/api/ActorTrustResource.java` — REST resource exposing computed actor trust scores
- `ledger/src/main/java/io/casehub/work/ledger/api/dto/LedgerEntryResponse.java` — DTO for ledger entry responses
- `ledger/src/main/java/io/casehub/work/ledger/api/dto/LedgerAttestationRequest.java` — DTO for attestation submission
- `ledger/src/main/java/io/casehub/work/ledger/api/dto/LedgerAttestationResponse.java` — DTO for attestation responses
- `ledger/src/main/java/io/casehub/work/ledger/api/dto/ActorTrustScoreResponse.java` — DTO for trust score query results
- `ledger/src/main/java/io/casehub/work/ledger/api/dto/ProvenanceRequest.java` — DTO for provenance link submission

#### Key Wiring

**`LedgerEventCapture @Observes WorkItemLifecycleEvent` (plain, no TransactionPhase).** The observer method signature is `void onWorkItemEvent(@Observes final WorkItemLifecycleEvent event)` — no `@Observes(during = TransactionPhase.AFTER_SUCCESS)`. This means the ledger entry is written synchronously within the same transaction as the lifecycle transition. If the lifecycle transaction rolls back, the ledger write also rolls back; no orphan entries are produced, but the entry is also not appended if the caller's transaction fails after the observer fires.

**Consumers using `casehub-work-ledger` must configure two Flyway locations.** `casehub-work-ledger` depends on `casehub-ledger`, whose base migrations (V1000–V1007) live at `classpath:db/ledger/migration`. The consuming application must set `quarkus.flyway.locations=classpath:db/work/migration,classpath:db/ledger/migration`. Without both paths, Hibernate fails to find the `ledger_entry` base table referenced by the JOINED inheritance.

**`actorType` derived from `actorId` prefix.** `LedgerEventCapture` maps the `actorId` string to an `ActorType` enum: `agent:` prefix → AGENT, `system:` prefix → SYSTEM, anything else → HUMAN. This convention is not enforced by casehub-work — it is a contract between callers and the ledger's trust scoring.

#### Architectural Decisions

**Why extract to shared `casehub-ledger` (ADR-0001):** Keeping ledger infrastructure in casehub-work would prevent Qhorus, casehub-engine, and devtown from using the same hash chain and trust scoring primitives. A shared `casehub-ledger` library lets all modules produce entries in the same chain. Tradeoff: casehub-work-ledger takes an external casehubio dependency, which requires casehub-ledger to be published before casehub-work can build.

**Why zero-core coupling via CDI observer:** The core extension (`casehub-work` runtime) has no import of `casehub-ledger` or `casehub-work-ledger`. The coupling is entirely through the CDI event bus — `WorkItemLifecycleEvent` is defined in the runtime; `LedgerEventCapture` is in the ledger module and observes it. Removing the ledger module from the classpath produces no compilation error and no runtime error in the core.

#### Pattern Introduced

Optional CDI observer module — a module that attaches to a domain event bus to add cross-cutting behaviour (audit, metrics, notification) without the core taking any dependency on it. The core fires a typed CDI event; optional modules observe it.

#### Pattern Anchor

`ledger/src/main/java/io/casehub/work/ledger/service/LedgerEventCapture.java` — `@Observes WorkItemLifecycleEvent` is the entire coupling surface

#### Gotchas

**Symptom:** `@QuarkusTest` in a module using `casehub-work-ledger` fails with "table LEDGER_ENTRY not found" or H2 schema errors.
**Cause:** The module's `application.properties` for tests only configures `quarkus.flyway.locations=classpath:db/work/migration`. The ledger base migrations at `classpath:db/ledger/migration` are not included.
**Fix:** Add `classpath:db/ledger/migration` to `quarkus.flyway.locations` in the module's `src/test/resources/application.properties`. Both paths are required when `casehub-work-ledger` is on the test classpath.

**Symptom:** Trust scores show HUMAN for all actors even when actorIds contain the `agent:` prefix.
**Cause:** `LedgerEventCapture` derives `actorType` from the `actorId` string at event time. If the workflow creates WorkItems with actorIds that don't use the `agent:` prefix convention (e.g. plain UUIDs for agent actors), all entries are stored as HUMAN.
**Fix:** Ensure agent-created WorkItems pass actorIds with the `agent:` prefix in `WorkItemService.create()` and other mutation methods. The convention is documented in the `LedgerEventCapture` Javadoc.

#### Pattern to Replicate

1. Define an optional module that depends on the core extension and a shared audit library.
2. In the optional module, define a CDI bean annotated with `@ApplicationScoped` that observes the core's lifecycle event type.
3. In the observer, persist an audit record that extends the shared audit library's base entity via JPA JOINED inheritance.
4. Ensure the optional module's Flyway migrations are scoped to its own path (`db/<module>/migration/`) and that the shared library's base migrations are a separate path consumers must configure.

### Layer — L6 Distribution

**Participates in chapters:** C26, C27
**Architectural patterns:** @DefaultBean CDI displacement, Observer, Pub-Sub
**Key protocols:** alternative-extension-patterns *(not yet written)*
**Issues:** Phase 18, Phase 19+20, #93, #147, #150, #155
**Navigation:** `git log --grep="#93\|#147\|#155" --oneline`
**Completed:** 2026-04-30 (SPI); 2026-05-01 (PostgreSQL backends)

#### What it adds

**Before:** `WorkItemResource` contained hardcoded in-process SSE delivery; no alternative broadcast backend was possible without modifying the runtime module.
**After:** `WorkItemEventBroadcaster` interface extracted; `LocalWorkItemEventBroadcaster @DefaultBean` provides in-process delivery; `PostgresWorkItemEventBroadcaster @ApplicationScoped @Alternative @Priority(1)` in `casehub-work-postgres-broadcaster` displaces it by classpath presence and fans events to all cluster nodes via PostgreSQL LISTEN/NOTIFY.

What this layer adds:
- **Broadcaster SPI** — `WorkItemEventBroadcaster` interface and `WorkItemQueueEventBroadcaster` interface; each has a `@DefaultBean` in-process implementation
- **PostgreSQL LISTEN/NOTIFY broadcaster** — `PostgresWorkItemEventBroadcaster` publishes to `casehub_work_events` channel; all nodes subscribed via LISTEN re-broadcast to local SSE clients; fires `AFTER_SUCCESS` only
- **Queue event broadcaster** — `PostgresWorkItemQueueEventBroadcaster` follows the same pattern for `casehub_work_queue_events`; `WorkItemQueueEvent` is a plain record — no separate wire DTO needed
- **No Flyway** — neither broadcaster module adds schema changes; they reuse the datasource managed by the core extension

Not closed here: audit trail for distributed events — ledger writes happen in the originating node before NOTIFY.

#### Accountability Gaps Closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| SSE clients on non-originating nodes see no events | Dashboard shows stale or empty inbox on all nodes except the one handling the mutation | PostgreSQL NOTIFY + LISTEN fan-out to all subscribed nodes |

#### Key Files

- `runtime/src/main/java/io/casehub/work/runtime/event/WorkItemEventBroadcaster.java` — the SPI interface defining the broadcast contract; all broadcaster implementations must implement this
- `runtime/src/main/java/io/casehub/work/runtime/event/LocalWorkItemEventBroadcaster.java` — `@DefaultBean` in-process implementation using a Mutiny `BroadcastProcessor`; active when no alternative is on the classpath
- `postgres-broadcaster/src/main/java/io/casehub/work/postgres/broadcaster/PostgresWorkItemEventBroadcaster.java` — `@ApplicationScoped @Alternative @Priority(1)` implementation; publishes to PostgreSQL LISTEN/NOTIFY and re-broadcasts to SSE clients on all nodes
- `postgres-broadcaster/src/main/java/io/casehub/work/postgres/broadcaster/WorkItemEventPayload.java` — wire DTO used by the PostgreSQL broadcaster to serialize only scalar fields before publishing to the NOTIFY channel
- `queues-postgres-broadcaster/src/main/java/io/casehub/work/queues/postgres/broadcaster/PostgresWorkItemQueueEventBroadcaster.java` — `@ApplicationScoped @Alternative @Priority(1)` queue event broadcaster; mirrors the WorkItem broadcaster pattern for queue events

#### Key Wiring

**`@ApplicationScoped @Alternative @Priority(1)` on `PostgresWorkItemEventBroadcaster`.** The `@Alternative` annotation removes the bean from consideration by default; `@Priority(1)` re-enables it globally without requiring an explicit `@Alternative` activation in `beans.xml`. When `casehub-work-postgres-broadcaster` is on the classpath, CDI selects `PostgresWorkItemEventBroadcaster` over the `@DefaultBean` `LocalWorkItemEventBroadcaster` — no application configuration required, classpath presence is sufficient.

**`TransactionPhase.AFTER_SUCCESS` in the NOTIFY observer.** Confirmed present: `@Observes(during = TransactionPhase.AFTER_SUCCESS)`. The NOTIFY fires only after the transaction commits — a rolled-back transaction never publishes to the channel, so SSE clients don't see phantom events for work items that were never actually persisted.

**`WorkItemEventPayload` wire DTO.** The PostgreSQL broadcaster serializes only scalar fields from `WorkItemLifecycleEvent` into `WorkItemEventPayload` before publishing. This avoids serializing complex object graphs over the NOTIFY channel (PostgreSQL NOTIFY payload has a 8000-byte limit). Subscriber nodes deserialize `WorkItemEventPayload` and reconstruct a minimal event for SSE delivery.

**No Flyway migrations.** Neither broadcaster module adds schema changes — they use the `reactive-pg-client` to subscribe to LISTEN channels on the existing datasource. No additional tables are created.

#### Architectural Decisions

**Why SPI extraction before PostgreSQL implementation:** Testing the local broadcaster doesn't require PostgreSQL. Extracting `WorkItemEventBroadcaster` as an interface (C26) before building the PostgreSQL backend (C27) means the local broadcaster's test coverage is independent of any infrastructure. Tradeoff: one extra abstraction layer, but zero infrastructure in unit tests.

**Why at-most-once delivery (AFTER_SUCCESS, no retry):** SSE clients reconnect on disconnect and receive the current state via REST — they don't require exactly-once delivery. PostgreSQL NOTIFY without acknowledgement is at-most-once: if the subscriber is disconnected during NOTIFY, the event is lost. For SSE this is acceptable because the client's reconnect triggers a fresh inbox load. Tradeoff: a client that disconnects during a burst of events misses those events and must poll to recover.

#### Pattern Introduced

@DefaultBean CDI displacement for broadcast backends — the SPI has a no-op or in-process @DefaultBean; a production backend adds @Alternative @Priority(1) and activates by classpath presence alone.

#### Pattern Anchor

`runtime/src/main/java/io/casehub/work/runtime/event/WorkItemEventBroadcaster.java` — the SPI interface
`runtime/src/main/java/io/casehub/work/runtime/event/LocalWorkItemEventBroadcaster.java` — the @DefaultBean in-process implementation

#### Gotchas

**Symptom:** `BackPressureFailure` appears in logs on every WorkItem lifecycle event, even when no SSE clients are connected.
**Cause:** `LocalWorkItemEventBroadcaster` uses a Mutiny `BroadcastProcessor`; if no subscribers are registered, publishing to it throws `BackPressureFailure`.
**Fix:** The broadcaster suppresses `BackPressureFailure` when no subscribers are active. If you see it in your own broadcaster implementation, wrap the publish call with a subscriber-count guard or suppress the specific exception.

**Symptom:** SSE events arrive on the originating node's clients but not on other cluster nodes' clients, even with `casehub-work-postgres-broadcaster` on the classpath.
**Cause:** The PostgreSQL broadcaster uses `reactive-pg-client` for LISTEN. If the reactive PG client datasource is not configured, the broadcaster initialises but never subscribes to the LISTEN channel.
**Fix:** Ensure `quarkus.datasource.reactive.url` is configured in the consuming application alongside the JDBC datasource. The broadcaster requires the reactive PG client; the core extension uses JDBC.
5. Do not add any import of the optional module or the shared audit library to the core extension — the coupling exists only through the CDI event.

### Layer — L7 Optional Modules

**Participates in chapters:** C5, C18, C19, C20, C21, C22, C23, C25, C26, C27
**Architectural patterns:** @DefaultBean CDI displacement, Strategy, SPI
**Key protocols:** alternative-extension-patterns *(not yet written)*, optional-module-pattern *(not yet written)*
**Issues:** Phase 5, #73, #106, #121, #124, #126, #140–#141, #142–#145, #156–#161
**Navigation:** `git log --grep="#121\|#156\|#142" --oneline`
**Completed:** 2026-04-14 through 2026-05-05

#### What it adds

**Before:** casehub-work-core provided only the task inbox; SLA reporting, AI routing, notifications, and external integrations required custom code in each consuming application.
**After:** Six optional modules activate by classpath presence — each displaces a `@DefaultBean` or adds a new capability with zero modification to the core extension.

What this layer adds:
- **AI module** (`casehub-work-ai`) — semantic worker selection + LLM-assisted resolution and escalation summary
- **SLA reports** (`casehub-work-reports`) — four pre-aggregated report endpoints with Caffeine cache
- **Notifications** (`casehub-work-notifications`) — HTTP webhook, Slack, Teams outbound via `NotificationChannel` SPI
- **Issue tracker** (`casehub-work-issue-tracker`) — bidirectional GitHub/Jira link; NormativeResolution drives WorkItem transitions from inbound webhooks
- **MongoDB** (`casehub-work-persistence-mongodb`) — drop-in `WorkItemStore` alternative for MongoDB deployments
- **Flow bridge** (`casehub-work-flow`) — `HumanTaskFlowBridge` wraps WorkItem creation as a Quarkus-Flow `function` task

Not closed here: distribution of events (L6), tamper-evident audit (L5).

#### Accountability Gaps Closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| No SLA reporting surface | Operators write ad-hoc HQL to query breach rates | `casehub-work-reports` pre-aggregated endpoints with Caffeine cache |
| No audit linkage between tasks and issue tracker | WorkItems and GitHub/Jira issues are disconnected | `casehub-work-issue-tracker` bidirectional link with inbound webhook handler |

#### Key Files

**AI module:**
- `ai/src/main/java/io/casehub/work/ai/skill/SemanticWorkerSelectionStrategy.java` — `@Alternative @Priority(1)` worker selection strategy using embedding-based skill matching; displaces the config-selected built-in strategy when the ai module is on the classpath
- `ai/src/main/java/io/casehub/work/ai/skill/EmbeddingSkillMatcher.java` — matches WorkItem skill requirements to worker profiles using vector embeddings; requires a configured LangChain4j embedding provider
- `ai/src/main/java/io/casehub/work/ai/suggestion/ResolutionSuggestionResource.java` — REST endpoint at `GET /workitems/{id}/resolution-suggestion`; calls `ResolutionSuggestionService` to produce LLM-assisted resolution text
- `ai/src/main/java/io/casehub/work/ai/escalation/EscalationSummaryObserver.java` — `@ApplicationScoped` CDI observer; listens for EXPIRED and CLAIM_EXPIRED lifecycle events and triggers LLM summary generation

**Reports module:**
- `reports/src/main/java/io/casehub/work/reports/api/ReportResource.java` — REST entry point exposing the four pre-aggregated report endpoints
- `reports/src/main/java/io/casehub/work/reports/service/ReportService.java` — service layer coordinating Caffeine-cached report assembly
- `reports/src/main/java/io/casehub/work/reports/service/SlaBreachReport.java` — SLA breach aggregation report model
- `reports/src/main/java/io/casehub/work/reports/service/ThroughputReport.java` — throughput-over-time report model

**Notifications module:**
- `api/src/main/java/io/casehub/work/api/NotificationChannel.java` — SPI interface in the api artifact; implemented by each channel
- `notifications/src/main/java/io/casehub/work/notifications/channel/HttpWebhookChannel.java` — outbound HTTP webhook implementation of `NotificationChannel`
- `notifications/src/main/java/io/casehub/work/notifications/channel/SlackNotificationChannel.java` — Slack outbound channel implementation
- `notifications/src/main/java/io/casehub/work/notifications/channel/TeamsNotificationChannel.java` — Microsoft Teams outbound channel implementation

**Issue tracker module:**
- `issue-tracker/src/main/java/io/casehub/work/issuetracker/spi/IssueTrackerProvider.java` — SPI interface for GitHub and Jira provider implementations
- `issue-tracker/src/main/java/io/casehub/work/issuetracker/webhook/WebhookEventHandler.java` — inbound webhook receiver; translates GitHub/Jira payloads to `NormativeResolution` and drives WorkItem transitions
- `api/src/main/java/io/casehub/work/api/NormativeResolution.java` — enum in the api artifact mapping tracker-specific resolution strings to stable WorkItem transition vocabulary

**MongoDB module:**
- `persistence-mongodb/src/main/java/io/casehub/work/mongodb/MongoWorkItemStore.java` — `@ApplicationScoped @Alternative @Priority(1)` drop-in replacement for the default JPA-based `WorkItemStore`; activates when `casehub-work-persistence-mongodb` is on the classpath

**Flow module:**
- `flow/src/main/java/io/casehub/work/flow/HumanTaskFlowBridge.java` — wraps WorkItem creation and completion as a Quarkus-Flow `function` task, enabling human task steps in CNCF Serverless Workflow definitions

#### Key Wiring

**`SemanticWorkerSelectionStrategy` `@ApplicationScoped @Alternative @Priority(1)`.** Displaces the config-selected built-in strategy (e.g. `ClaimFirstStrategy`) when `casehub-work-ai` is on the classpath. Delegates skill matching to `EmbeddingSkillMatcher`, which requires a configured LangChain4j embedding provider. If no embedding provider is configured, `EmbeddingSkillMatcher.matchSkills()` returns empty results and the strategy falls back to the configured fallback strategy.

**`EscalationSummaryObserver` observes `WorkItemLifecycleEvent`.** When EXPIRED or CLAIM_EXPIRED is received, the observer calls `EscalationSummaryService` which sends the WorkItem context to an LLM and stores the response in the `escalation_summary` table (V4001 migration). The V4001 migration lives at `ai/src/main/resources/db/work/migration/V4001__escalation_summary.sql`.

**`NormativeResolution` enum bridges issue tracker vocabulary to WorkItem transitions.** GitHub `closed/completed` → DONE → WorkItem COMPLETED. GitHub `closed/not_planned` → DECLINE → WorkItem CANCELLED. Jira `Won't Do` → DECLINE → WorkItem CANCELLED. Jira `Done` → DONE → WorkItem COMPLETED. `WebhookEventHandler` translates inbound webhook payloads to `NormativeResolution` and calls `WorkItemService` directly. For the complete normative mapping — all 12 WorkItemStatus values to CommitmentState, all 26 WorkEventType values to MessageType — see `docs/NORMATIVE-ALIGNMENT.md` (#159).

**`MongoWorkItemStore` `@ApplicationScoped @Alternative @Priority(1)`.** CDI selects this implementation over the default JPA-based `WorkItemStore` when `casehub-work-persistence-mongodb` is on the classpath. No configuration flag is required — classpath presence alone activates the MongoDB backend.

#### Architectural Decisions

**Why NormativeResolution enum rather than direct state mapping:** Each issue tracker uses different vocabulary for the same semantic outcome ("Won't Do", "Invalid", "Not Planned" all mean DECLINE). `NormativeResolution` provides a stable vocabulary layer between tracker-specific strings and `WorkItemService` transitions. Tradeoff: mapping tables in `WebhookEventHandler` must be maintained when trackers add new resolution names.

**Why LLM assist in the same module as semantic routing (ai/):** Both capabilities require an LLM provider on the classpath. Co-locating them in `casehub-work-ai` means a consuming application adds one optional dependency to get both — separate modules would require two dependencies for two capabilities that share the same infrastructure requirement.

#### Pattern Introduced

Optional classpath-presence activation — an optional module adds `@Alternative @Priority(1)` implementations or new CDI beans; the core extension has no knowledge of the module; consumers add it as a dependency to activate its behaviour.

#### Pattern Anchor

`ai/src/main/java/io/casehub/work/ai/skill/SemanticWorkerSelectionStrategy.java` — `@Alternative @Priority(1)` displaces the config-selected built-in strategy when the ai module is on the classpath

#### Gotchas

**Symptom:** `SemanticWorkerSelectionStrategy` activates but worker assignment always falls back to `LeastLoadedStrategy`.
**Cause:** `EmbeddingSkillMatcher` requires an embedding model provider (e.g. via LangChain4j). If no provider is configured, `EmbeddingSkillMatcher.matchSkills()` returns an empty result and `SemanticWorkerSelectionStrategy` falls back to the configured fallback strategy.
**Fix:** Configure an embedding model in `application.properties` (e.g. `quarkus.langchain4j.openai.api-key=...`). Without a live embedding provider, semantic matching is a no-op.

**Symptom:** `WebhookEventHandler` receives a GitHub webhook with `closed/not_planned` but the linked WorkItem is not cancelled.
**Cause:** The resolution→NormativeResolution mapping is driven by both the `state` and the `state_reason` fields of the GitHub issue event. `closed` alone without `state_reason=not_planned` maps to DONE, not DECLINE.
**Fix:** Ensure the GitHub webhook is configured to send `issues` events (not just `push` events). Verify the `state_reason` field is present in the payload — older GitHub webhook versions may omit it.

#### Pattern to Replicate

1. Define an optional module as a Quarkus extension (with deployment module) that depends only on the core's `api/` artifact.
2. Implement `@Alternative @Priority(1)` overrides for SPIs defined in `api/` — the override activates when the module is on the classpath.
3. For new capabilities with no SPI to override (e.g. reports, LLM assist), use `@ApplicationScoped` CDI beans and REST resources that activate by presence.
4. Ensure the optional module carries its own Flyway migrations at `db/work/migration/` in a reserved V-number range (see flyway-version-range-allocation *(not yet written)*).
5. Write Jandex index for the module (via jandex-maven-plugin) so CDI can discover its beans in Quarkus without a full classpath scan.

### Layer — L8 Federation

**Participates in chapters:** —
**Architectural patterns:** Decorator, Shadow Replication, Pub-Sub (filter-on-creation + lock-on), Proxy
**Key protocols:** —
**Issues:** #92, #95
**Navigation:** `git log issue-92-distributed-workitems --grep="#92\|#95" --oneline`
**Completed:** in progress (issue-92-distributed-workitems branch)

#### What it adds

**Before:** WorkItems exist only within a single Quarkus application. A WorkItem created in service A is invisible to service B. No mechanism exists for cross-service task distribution, and local mutations of remotely-owned items are uncontrolled.
**After:** `casehub-work-federation` enables bidirectional cross-service WorkItem sharing via a publish-subscribe model. Outbound: `FederationEventRouter` publishes lifecycle events as CloudEvents to peer services that match subscription filters. Inbound: `FederationReceiver` creates read-only shadow copies with HMAC-verified integrity and version-based staleness rejection. `FederationGuardStore` enforces shadow immutability at the store layer. `FederationProxyService` transparently routes mutations on shadow items back to the owning service via `WorkItemClient` (a zero-dependency REST client in the `client/` module).

What this layer adds:
- **Shadow replication** — `FederationReceiver` processes inbound CloudEvents, creates/updates local shadow WorkItems with `originServiceId` + `originWorkItemId` + `originVersion` fields; rejects stale updates where incoming version ≤ existing version
- **Shadow immutability guard** — `FederationGuardStore` is a `@Decorator` on `WorkItemStore.put()` that blocks all mutations on shadow items (those with `originServiceId` set) unless `FederationSyncContext` is active (ThreadLocal flag set by `FederationReceiver` during sync)
- **Transparent proxy mutations** — `FederationProxyService` is a `@Decorator` on `WorkItemOperations` that intercepts claim/complete/reject/delegate/release on shadow items and routes them to the owning service via `FederationProxy` → `WorkItemClient` REST calls
- **Subscription model** — `FederationSubscriptionService` manages peer registrations with `callbackUrl`, `baseUrl`, `tenancyId`, `SubscriptionFilter` (candidateGroups, candidateUsers), HMAC secret, and auto-suspend after 5 consecutive delivery failures
- **Outbound event distribution** — `FederationEventRouter` observes `WorkItemLifecycleEvent` during `TransactionPhase.AFTER_SUCCESS`; creation events trigger filter-based subscription matching + lock-on; subsequent events reuse locked subscriptions; terminal events clean up tracking
- **HMAC transport security** — `HmacSigner` signs outbound CloudEvents and verifies inbound signatures; per-subscription secrets
- **Lightweight REST client** — `WorkItemClient` (in `client/` module) is pure Java with no Quarkus/CDI/JPA dependencies; five operations (claim, complete, reject, delegate, release); returns `ClientResponse` record with status code and optional JSON body

Not closed here: cross-service WorkItem creation (#95 federation path), Qhorus event bus integration (#97), federated audit trail reconciliation.

#### Accountability Gaps Closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| No cross-service task visibility | WorkItems created in service A are invisible to agents/humans in service B | Shadow replication via `FederationReceiver` — CloudEvent-driven projections appear in the local inbox |
| Uncontrolled shadow mutation | Local code could mutate a shadow item, diverging from the owning service's authoritative state | `FederationGuardStore` `@Decorator` blocks `put()` on shadow items unless `FederationSyncContext` is active |
| No mutation routing for shadows | Claiming or completing a shadow item would modify only the local copy with no effect on the real item | `FederationProxyService` `@Decorator` intercepts lifecycle operations on shadows and proxies them to the owning service |
| No delivery reliability tracking | Failed event deliveries to peer services would go unnoticed | `FederationSubscriptionService.recordFailure()` tracks consecutive failures; auto-suspends subscription after 5 |

#### Key Files

**Federation module:**

`federation/src/main/java/io/casehub/work/federation/FederationGuardStore.java` — `@Decorator @Priority(APPLICATION)` on `WorkItemStore`; `put()` throws `FederatedWorkItemMutationException` if `originServiceId` is set and `FederationSyncContext` is not active. `get()` and `scan()` delegate transparently.

`federation/src/main/java/io/casehub/work/federation/FederationReceiver.java` — `@ApplicationScoped` inbound CloudEvent processor; verifies HMAC, parses CloudEvent JSON, runs in tenant context via `TenantContextRunner`, reconciles shadows by `(originServiceId, originWorkItemId)` tuple, rejects stale versions, namespaces `callerRef` to `federation:{sourceServiceId}:{originalCallerRef}`.

`federation/src/main/java/io/casehub/work/federation/FederationEventRouter.java` — `@ApplicationScoped` outbound event distributor; `@Observes(during = TransactionPhase.AFTER_SUCCESS) WorkItemLifecycleEvent`; skips shadow items (`originServiceId != null`); creation events → filter matching + lock-on; non-creation events → locked subscription lookup; terminal events → tracking cleanup.

`federation/src/main/java/io/casehub/work/federation/FederationProxyService.java` — `@Decorator @Priority(APPLICATION + 10)` on `WorkItemOperations`; intercepts claim, complete, reject, delegate, release on shadow items (detected by `originServiceId != null`); delegates to `FederationProxy` for remote execution. All other operations pass through to the local delegate.

`federation/src/main/java/io/casehub/work/federation/FederationProxy.java` — `@ApplicationScoped` bridge to `WorkItemClient`; resolves the owning service's base URL from active subscriptions matching `shadow.originServiceId`; maps `ClientResponse` to success/409 Conflict/503 Unavailable.

`federation/src/main/java/io/casehub/work/federation/FederationSyncContext.java` — `ThreadLocal<Boolean>` guard; `activate()` returns an `AutoCloseable` that sets the flag; `FederationGuardStore` checks `isActive()` before allowing shadow mutation. Used exclusively by `FederationReceiver` in a try-with-resources block.

`federation/src/main/java/io/casehub/work/federation/FederationConfig.java` — SmallRye `@ConfigMapping(prefix = "casehub.work.federation")`; `serviceId()` (default: `"default"`) identifies this service in CloudEvent `source` URN; `proxyTimeoutSeconds()` (default: `5`) configures `WorkItemClient` timeout.

`federation/src/main/java/io/casehub/work/federation/subscription/FederationSubscriptionService.java` — `@ApplicationScoped` subscription lifecycle; `register()` creates subscription with peerId, callbackUrl, baseUrl, tenancyId, filter, capabilities, HMAC secret; `matchSubscriptions()` evaluates `SubscriptionFilterEvaluator` against WorkItem; `lockOn()` creates tracking entry; `recordSuccess()`/`recordFailure()` track delivery; auto-suspends after 5 consecutive failures.

`federation/src/main/java/io/casehub/work/federation/subscription/FederationSubscriptionEntity.java` — Panache entity; `federation_subscription` table (V8000); `SubscriptionStatus` enum (ACTIVE, SUSPENDED).

`federation/src/main/java/io/casehub/work/federation/subscription/FederationTrackingEntity.java` — Panache entity; `federation_subscription_tracking` table (V8000); composite PK `(subscriptionId, workItemId)`.

`federation/src/main/java/io/casehub/work/federation/subscription/SubscriptionFilter.java` — record with `candidateGroups`, `candidateUsers`, `tenancyId`; defensive copies via `List.copyOf()`.

`federation/src/main/java/io/casehub/work/federation/subscription/SubscriptionFilterEvaluator.java` — stateless matcher; tenancyId must match; candidateGroups/candidateUsers intersected against WorkItem's CSV fields; empty filter matches all items in the tenant.

`federation/src/main/java/io/casehub/work/federation/transport/FederationTransport.java` — SPI interface for outbound event delivery; `send(cloudEventJson, callbackUrl, hmacSecret)`.

`federation/src/main/java/io/casehub/work/federation/transport/WebhookFederationTransport.java` — HTTP POST implementation of `FederationTransport`.

`federation/src/main/java/io/casehub/work/federation/transport/HmacSigner.java` — HMAC-SHA256 signing and verification for CloudEvent payloads.

`federation/src/main/java/io/casehub/work/federation/rest/FederationEventResource.java` — JAX-RS endpoint for receiving inbound federation CloudEvents.

`federation/src/main/java/io/casehub/work/federation/rest/FederationSubscriptionResource.java` — JAX-RS endpoint for subscription CRUD.

`federation/src/main/resources/db/work/migration/V8000__federation_subscription.sql` — creates `federation_subscription` and `federation_subscription_tracking` tables with index on `work_item_id`.

**Client module:**

`client/src/main/java/io/casehub/work/client/WorkItemClient.java` — pure Java HTTP client using `java.net.http.HttpClient`; five operations (claim, complete, reject, delegate, release); `ClientResponse` record with `statusCode`, `body` (JsonNode), and convenience predicates (`isSuccess()`, `isConflict()`, `isUnavailable()`). No Quarkus, CDI, or JPA dependency.

#### Key Wiring

**`FederationGuardStore` `@Decorator @Priority(APPLICATION)` on `WorkItemStore`.** The decorator intercepts `put()` only — `get()` and `scan()` delegate transparently. The guard checks two conditions: `item.originServiceId() != null` (this is a shadow) AND `!FederationSyncContext.isActive()` (we're not inside a federation sync). Both must be true to throw. This ensures that only `FederationReceiver` (which activates the sync context) can write shadow items; all other code paths — including `WorkItemService`, REST handlers, and integration modules — are blocked from mutating shadows.

**`FederationProxyService` `@Decorator @Priority(APPLICATION + 10)` on `WorkItemOperations`.** Priority +10 ensures it wraps after `FederationGuardStore` (which is at APPLICATION). The proxy only intercepts five "actor" operations (claim, complete, reject, delegate, release) on shadow items. All other operations — including create, start, suspend, resume, cancel, fault, obsolete, escalate — delegate directly. This is intentional: shadows should not be created locally (they arrive via `FederationReceiver`), and administrative operations like suspend/cancel are not proxied in the current design.

**`FederationSyncContext` ThreadLocal bypass.** `FederationReceiver.processInTenantContext()` wraps `workItemStore.put(projection)` in `try (var ctx = FederationSyncContext.activate()) { ... }`. The `AutoCloseable` pattern ensures the ThreadLocal is always cleaned up, even on exception. The decorator checks `isActive()` synchronously within the same thread — no async boundary can intervene.

**Filter-on-creation + lock-on subscription model.** `FederationEventRouter` evaluates `SubscriptionFilterEvaluator` only on creation events (`*.created`). Matching subscriptions are locked to the WorkItem via `FederationTrackingEntity(subscriptionId, workItemId)`. All subsequent lifecycle events for that WorkItem reuse the locked subscriptions without re-evaluating filters. This avoids re-evaluation cost on every event and ensures a WorkItem is consistently routed to the same set of peers for its entire lifecycle. Terminal events trigger `removeTracking()` cleanup.

**`@Observes(during = TransactionPhase.AFTER_SUCCESS)` on `FederationEventRouter`.** Outbound federation events fire only after the transaction commits — a rolled-back transition never publishes to peer services. This matches the L6 broadcaster pattern.

**Shadow items skip outbound routing.** `FederationEventRouter.onWorkItemLifecycle()` returns immediately if `workItem.originServiceId() != null`. This prevents federation echo: a shadow update received from service A is not re-published to other subscribers, avoiding infinite event loops in multi-service topologies.

**`callerRef` namespacing.** `FederationReceiver` rewrites `callerRef` to `federation:{sourceServiceId}:{originalCallerRef}`. This preserves the original callerRef for traceability while ensuring shadow callerRefs don't collide with locally-created items that may share the same original callerRef value.

**`FederationProxy` resolves owner URL from subscriptions.** When proxying a mutation on a shadow item, `FederationProxy` looks up active subscriptions with `peerId` matching `shadow.originServiceId()` to find the `baseUrl`. This means the subscription table serves double duty: outbound event routing AND inbound proxy URL resolution.

**Flyway V8000 at `db/work/migration/`.** Two tables: `federation_subscription` (subscription lifecycle) and `federation_subscription_tracking` (lock-on pairs). V8000 is in the federation module's reserved range.

#### Architectural Decisions

**Why `@Decorator` pattern rather than a separate `FederatedWorkItemStore` implementation:** A decorator wraps any underlying store — JPA, MongoDB, in-memory — without knowing which one is active. A separate implementation would need to duplicate or compose with every store variant. The decorator pattern also integrates naturally with CDI's priority-based selection, and the guard check (`originServiceId != null && !FederationSyncContext.isActive()`) is orthogonal to storage concerns. Tradeoff: decorators are globally active when the module is on the classpath — there is no way to disable the guard without removing the module.

**Why shadow immutability rather than conflict resolution:** Allowing local mutations on shadow items would require a conflict resolution strategy (last-writer-wins, merge, vector clocks). Shadow immutability is simpler: the owning service is the single source of truth; mutations are proxied back to it. Tradeoff: shadow items have higher write latency (remote call required) and are unavailable when the owning service is down.

**Why filter-on-creation + lock-on rather than re-evaluate on every event:** Re-evaluating subscription filters on every lifecycle event would be correct but expensive — each event would scan all active subscriptions and evaluate filters. Lock-on at creation time means subsequent events are O(1) lookup by workItemId. Tradeoff: if a subscription's filter changes after lock-on, the change does not affect already-locked WorkItems. This is acceptable because subscriptions represent stable service-to-service relationships, not dynamic query surfaces.

**Why zero-dependency client module:** `WorkItemClient` uses only `java.net.http.HttpClient` and Jackson (already on any Quarkus classpath). No Quarkus, CDI, or JPA dependency means it can be used in standalone Java applications, CLI tools, or test harnesses without pulling in the extension framework. Tradeoff: no MicroProfile REST Client features (retry, circuit breaker, health) — consumers must handle resilience themselves.

**Why auto-suspend after 5 consecutive failures:** A failing subscription generates error logs and wasted HTTP calls on every lifecycle event. Auto-suspend limits the blast radius. The threshold (5) is hardcoded — no configuration property. Tradeoff: a transient outage lasting more than 5 events permanently suspends the subscription; manual re-activation is required.

#### Pattern Introduced

Decorator-guarded shadow replication — a `@Decorator` on the store SPI enforces write protection on replicated items using a ThreadLocal context flag; only the replication receiver can bypass the guard. Combined with a second `@Decorator` on the operations SPI for transparent proxy routing, this creates a bidirectional federation where local consumers interact with shadow items identically to local items, but mutations are transparently routed to the owning service.

#### Pattern Anchor

`federation/src/main/java/io/casehub/work/federation/FederationGuardStore.java` — the `@Decorator` that enforces shadow immutability
`federation/src/main/java/io/casehub/work/federation/FederationProxyService.java` — the `@Decorator` that proxies shadow mutations to the owning service

#### Gotchas

**Symptom:** Inbound federation events are silently discarded with log message "Stale federation event discarded."
**Cause:** `FederationReceiver` rejects events where `incomingVersion <= shadow.originVersion()`. If the owning service sends events out of order (e.g. due to retry or parallel processing), later events may arrive with a lower version than an earlier-processed event.
**Fix:** Ensure the owning service publishes events with monotonically increasing `WorkItem.version` (guaranteed by `@Version` OCC). If events are retried, the retry carries the same version — a duplicate is harmless (version ==, rejected). True out-of-order delivery (version < current) indicates a bug in the transport layer or a concurrent modification on the owning service.

**Symptom:** `FederatedWorkItemMutationException` thrown when `WorkItemService` tries to update a shadow item during a legitimate federation sync.
**Cause:** `FederationSyncContext` is ThreadLocal. If `FederationReceiver.processInTenantContext()` runs on a different thread than `workItemStore.put()` (e.g. due to a Vert.x context switch or `@Transactional(REQUIRES_NEW)` spawning a new thread), the ThreadLocal flag is not visible.
**Fix:** Ensure `FederationReceiver` processing stays on the same thread from `FederationSyncContext.activate()` through `workItemStore.put()`. Do not use `REQUIRES_NEW` or async dispatch within the sync path.

**Symptom:** A peer service's subscription is permanently suspended after a brief network outage.
**Cause:** `FederationSubscriptionService.recordFailure()` increments `consecutiveFailures` and auto-suspends at 5. A burst of lifecycle events during the outage reaches the threshold quickly.
**Fix:** Manual re-activation required — update the `federation_subscription` row to set `status = 'ACTIVE'` and `consecutive_failures = 0`. Consider adding a REST endpoint for subscription re-activation in a future iteration.

**Symptom:** Shadow items appear with `callerRef = null` even though the original WorkItem had a callerRef.
**Cause:** `FederationReceiver` reads `callerRef` from `data.path("callerRef")` in the CloudEvent payload. If the owning WorkItem's callerRef was null, the namespacing logic produces null (the `projection.callerRef() != null` check returns false).
**Fix:** This is by design — shadow items with no original callerRef get no namespaced callerRef.

#### Pattern to Replicate

1. Define a `@Decorator @Priority(APPLICATION)` on the domain's store SPI that guards write operations on replicated items. Use a ThreadLocal context flag (with `AutoCloseable` cleanup) to allow the replication receiver to bypass the guard.
2. Define a second `@Decorator @Priority(APPLICATION + N)` on the domain's operations SPI that intercepts actor operations on replicated items and proxies them to the owning service via a lightweight REST client.
3. Implement an inbound receiver that validates transport security (HMAC), reconciles items by `(originServiceId, originItemId)` tuple with version-based staleness rejection, and activates the ThreadLocal context before writing.
4. Implement an outbound event router that observes domain lifecycle events during `AFTER_SUCCESS`, evaluates subscription filters on creation events only, locks matching subscriptions, and reuses locked subscriptions for subsequent events. Skip outbound routing for replicated items to prevent echo loops.
5. Define a subscription model with filter, callback URL, HMAC secret, and delivery reliability tracking (consecutive failure count with auto-suspend threshold).
6. Package the REST client as a separate zero-dependency module so it can be used by the federation proxy and by standalone consumers without pulling in the extension framework.
7. Reserve a Flyway version range (V8000+) for federation schema (subscription + tracking tables).

---

## §10 Architectural Decisions

All 10 ADRs are chapter-specific and elaborated in §9.4 layer entries. This section provides cross-references only.

| ADR | Decision | Where elaborated |
|---|---|---|
| ADR-0001 | Extract ledger infrastructure to shared `casehub-ledger` library so Qhorus, engine, and devtown can use the same hash chain | §9.4 L5 — Architectural decisions |
| ADR-0002 | Labels as queues with MANUAL/INFERRED persistence — MANUAL labels survive filter re-evaluation; INFERRED labels are recomputed | §9.4 L4 — Architectural decisions |
| ADR-0003 | Capability as a validated value type with kebab-case enforcement — prevents silent empty candidate pools from misspelled tags | §9.4 L1 — Architectural decisions |
| ADR-0004 | Capability validation mode as deployment config (STRICT/WARN/PERMISSIVE) — not hardcoded — consuming apps choose validation strictness | §9.4 L1 — Architectural decisions |
| ADR-0005 | Group membership snapshot at WorkItem creation — `excludedGroups` expanded to actor IDs at instantiation so exclusion is deterministic at execution time | §9.4 L1 — Architectural decisions |
| ADR-0006 | Evaluate outcome conditions at completion time, not at instantiation — condition expressions reference completion-time context (`resolution`, `actorId`, `reason`) unavailable at instantiation | §9.4 L1 — Architectural decisions |
| ADR-0007 | Use JEXL as the condition expression language for outcomes — reuses existing `JexlConditionEvaluator` with zero new dependencies; one syntax for filter rules and outcome conditions | §9.4 L1 — Architectural decisions |
| ADR-0008 | Application-level tenant filtering — WHERE clauses via `CurrentPrincipal.tenancyId()` work identically across JPA, MongoDB, and InMemory backends; RLS layered as defence-in-depth | §9.4 L1 — Architectural decisions |
| ADR-0009 | TenantContextRunner for async and background work — single programmatic CDI context activation pattern for Quartz jobs, `@ObservesAsync`, and startup recovery; nestable and virtual-thread safe | §9.4 L1 — Architectural decisions |
| ADR-0010 | Cross-tenant access via type-safe `@CrossTenant` CDI qualifier — separate store interfaces with producer validation enforce least-privilege; accidental injection in request-scoped code fails at startup | §9.4 L1 — Architectural decisions |

Full decision records: `docs/adr/`

---

## §11 Quality Requirements

| Quality goal | Scenario | Verification |
|---|---|---|
| SLA correctness | WorkItem with `expiresAt` in the past transitions to EXPIRED within one `ExpiryTimerJob` cycle | `ExpiryLifecycleServiceTest` verifies EXPIRED transition; scheduler interval configurable via `casehub.work.cleanup.expiry-check-seconds` |
| SLA breach exhaustion | Chained policy with two failing branches sets WorkItem to ESCALATED (terminal) | `ExpiryLifecycleServiceTest.checkExpired_chainedExhaustion` |
| Isolation | Core extension compiles and passes unit tests without `casehub-ledger`, `casehub-qhorus`, or `casehub-engine` on the classpath | `mvn verify -pl runtime` with no optional modules present |
| Zero-datasource unit tests | `WorkItemServiceTest` runs without Quarkus boot in under 1 s | `InMemoryWorkItemStore @Alternative @Priority(100)` in `casehub-work-persistence-memory`; no `@QuarkusTest` annotation required |
| Native image correctness | All 25 `@QuarkusIntegrationTest` tests pass against GraalVM 25 native binary | `mvn verify -Pnative -pl integration-tests` |
| Concurrent claim safety | Two simultaneous claim requests on different nodes produce one ASSIGNED and one HTTP 409 Conflict | `WorkItemResourceTest` with concurrent claim simulation; `@Version` on WorkItem triggers `OptimisticLockException` |

### Test Totals (all modules, 0 failures)

| Module | Tests |
|---|---|
| casehub-work-api | 82 |
| casehub-work-core | 38 |
| runtime | 840 |
| casehub-work-flow | 32 |
| casehub-work-ledger | 76 |
| casehub-work-queues | 84 |
| casehub-work-ai | 77 |
| casehub-work-examples | 18 |
| casehub-work-queues-examples | 37 |
| casehub-work-reports | 73 |
| casehub-work-notifications | 39 |
| casehub-work-postgres-broadcaster | 22 |
| casehub-work-queues-postgres-broadcaster | 13 |
| casehub-work-issue-tracker | 93 |
| testing | 31 |
| integration-tests | 25 |
| **Total** | **~1278** |

---

## §12 Risks and Technical Debt

### Active Risks

| Risk | Impact | Status |
|---|---|---|
| `casehub-work-qhorus` not built | MCP tools for agent-driven approval flows unavailable | ⏸ Blocked — Qhorus APIs not stable |
| `casehub-work-casehub` not built | CaseHub WorkerRegistry adapter unavailable | ⏸ Blocked — CaseHub engine API not stable |
| ProvenanceLink (#39) | PROV-O causal graph across WorkItems, cases, and agent activities unavailable | ⏸ Blocked — awaiting CaseHub + Qhorus integrations |

### Technical Debt

| Item | Detail |
|---|---|
| Notification duplication | `casehub-work-notifications` ships Slack/Teams directly; delegation to `casehub-connectors` connector SPI tracked in casehubio/parent#5 (closed) |
| `WorkItemLabel.path` still String | `LabelDefinition.path` migrated to `casehub-platform-api Path` type (C34); `WorkItemLabel.path` migration deferred — M-scale, requires REST API change |
| queues-dashboard Tamboui dependency | Tamboui UI framework is a POC dependency; not production-hardened |

### Layer Churn Observations

- **L1 Domain Baseline** participates in 20+ chapters across all three journeys — the highest delta rate. Expected: every new capability adds at least one field or service method. The boundary is correct; churn reflects richness, not a design problem.
- **L3 Lifecycle Engine** has High deltas in C33 and C35 (2026-05-22 and 2026-06-03). Two lifecycle-correctness passes after the initial engine build. The initial lifecycle design (C3, 2026-04-14) under-specified terminal states; C33+C35 closed the gaps.

---

## §13 Glossary

| Term | Definition |
|---|---|
| WorkItem | A unit of work requiring human attention or judgment — has assignee, expiry, delegation, and full audit trail; persists minutes to days; a WorkItem waits for a human |
| Task (CNCF Serverless Workflow) | A machine-executed workflow step in milliseconds; no assignee, no expiry; controlled by the engine |
| Task (CaseHub) | A CMMN case work unit assigned to any worker (human or agent) via capability matching |
| SLA | Service-Level Agreement deadline on a WorkItem — either `claimDeadline` (must be claimed by) or `expiresAt` (must be completed by) |
| SlaBreachPolicy | SPI deciding what happens when a completion SLA breaches: Fail (terminal EXPIRED), EscalateTo (new candidates, new deadline), Extend (new deadline), Chained (try multiple), or Exhausted (terminal ESCALATED) |
| ClaimSlaPolicy | SPI deciding what happens when a claim SLA breaches: item unclaimed too long |
| BreachDecision | Sealed type returned by `SlaBreachPolicy.onBreach()` — five variants: Fail, EscalateTo, Extend, Chained, Exhausted |
| callerRef | Opaque routing key set on a WorkItem at creation; echoed in every lifecycle event; convention: `casehub-engine` sets `"case:{caseId}/pi:{planItemId}"` (HumanTask) or `"case:{caseId}/gate:{gateId}"` (ActionGate); casehub-work never interprets it |
| DELEGATED | WorkItemStatus set when `delegate()` is called — pre-acceptance state; the named target must `accept-delegation` or `decline-delegation`; non-terminal, expires normally |
| ESCALATED | WorkItemStatus set when a Chained SlaBreachPolicy exhausts all branches — terminal; requires operator intervention |
| EXPIRED | WorkItemStatus set when the completion deadline passes and SlaBreachPolicy returns Fail or Chained exhausts — terminal |
| DelegationDeclineTarget | Enum (POOL / DELEGATOR) — where a declined delegation returns the WorkItem; instance field takes precedence over scope preference |
| Chapter | A delivery unit in Arc42Stories — a vertical cut through architectural layers delivering one platform-visible capability end-to-end |
| Journey | A major arc of related Chapters; casehub-work has three: Core Platform, Enterprise Capabilities, Lifecycle Enrichment |
| Layer | A horizontal architectural concern; casehub-work defines seven internal layers (L1–L7) |
| NormativeResolution | Enum (DONE / DECLINE / FAILURE) bridging issue tracker vocabulary to WorkItem transitions; defined in `casehub-work-api`; see `docs/NORMATIVE-ALIGNMENT.md` §7 |
| Speech act | A communicative act in the Qhorus normative model — one of 9 types: QUERY, COMMAND, RESPONSE, STATUS, DECLINE, HANDOFF, DONE, FAILURE, EVENT; WorkItem lifecycle events map to these via `docs/NORMATIVE-ALIGNMENT.md` §4 |
| CommitmentState | Lifecycle state of an obligation in the Qhorus CommitmentStore — OPEN, ACKNOWLEDGED, FULFILLED, DECLINED, FAILED, DELEGATED, EXPIRED; WorkItemStatus maps to these via `docs/NORMATIVE-ALIGNMENT.md` §3 |
| Normative alignment | The documented mapping between casehub-work lifecycle concepts and Qhorus normative concepts; see `docs/NORMATIVE-ALIGNMENT.md` |
