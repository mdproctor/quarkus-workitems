# Quarkus WorkItems — Design Document

## Overview

Quarkus WorkItems provides human-scale WorkItem lifecycle management for the Quarkus
ecosystem. Any Quarkus application adds `quarkus-workitems` as a dependency and gets a
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
| Parent | `quarkus-workitems-parent` | BOM, version management |
| Runtime | `quarkus-workitems` | Core — WorkItem model, storage SPI, JPA defaults, service, REST API, lifecycle engine, labels, vocabulary |
| Deployment | `quarkus-workitems-deployment` | Build-time processor — feature registration, native config |
| Testing | `quarkus-workitems-testing` | `InMemoryWorkItemRepository` — no datasource needed for unit tests |
| Flow | `quarkus-workitems-flow` | Quarkus-Flow integration — `WorkItemsFlow` DSL base class, `HumanTaskFlowBridge`, `WorkItemFlowEventListener` |
| Ledger | `quarkus-workitems-ledger` | Optional accountability module — command/event ledger, SHA-256 hash chain, peer attestation, EigenTrust reputation. Extends `io.quarkiverse.ledger:quarkus-ledger` (shared base library — see ADR-0001). Zero core impact when absent. |
| Queues | `quarkus-workitems-queues` | Optional label-based work queues — `WorkItemFilter` (JEXL/JQ/Lambda), `FilterChain` derivation graph with cascade delete, `QueueView` named label-pattern queries, soft assignment (`relinquishable` flag). See ADR-0002. Zero core impact when absent. |
| Examples | `quarkus-workitems-examples` | Runnable scenario demos — 4 `@QuarkusTest` scenarios covering every ledger/audit capability via `POST /examples/{name}/run` |
| Flow Examples | `quarkus-workitems-flow-examples` | WorkItemsFlow DSL showcase — contract review workflow mixing automated `function()` and human `workItem()` steps |
| Integration Tests | `integration-tests` | Black-box `@QuarkusIntegrationTest` suite and native image validation |
| *(future)* | `quarkus-workitems-casehub` | CaseHub `WorkerRegistry` adapter (blocked: CaseHub not ready) |
| *(future)* | `quarkus-workitems-qhorus` | Qhorus MCP tools (blocked: Qhorus not ready) |
| *(future)* | `quarkus-workitems-mongodb` | MongoDB-backed `WorkItemRepository` |
| *(future)* | `quarkus-workitems-redis` | Redis-backed `WorkItemRepository` |

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
| Native image | GraalVM 25 (target) | Validation planned after Phase 4 |

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

### AuditEntry (`runtime/model/`)
Append-only event log: `workItemId`, `event`, `actor`, `detail` (JSON), `occurredAt`.

Audit event values (aligned with quarkus-flow task event naming):
`CREATED` | `ASSIGNED` | `STARTED` | `COMPLETED` | `REJECTED` | `DELEGATED` | `RELEASED` | `SUSPENDED` | `RESUMED` | `CANCELLED` | `EXPIRED` | `ESCALATED`

---

## Storage SPI

Two interfaces in `runtime.repository` allow pluggable persistence:

| Interface | Default impl | Purpose |
|---|---|---|
| `WorkItemRepository` | `JpaWorkItemRepository` | save, findById, findAll, findInbox (OR across assignee/groups/users), findExpired, findUnclaimedPastDeadline |
| `AuditEntryRepository` | `JpaAuditEntryRepository` | append, findByWorkItemId |

Default JPA implementations are `@ApplicationScoped`. Alternatives (in-memory, MongoDB, Redis)
override via `@Alternative @Priority(1)`. The `quarkus-workitems-testing` module provides
`InMemoryWorkItemRepository` + `InMemoryAuditEntryRepository` — no datasource required,
enabling plain unit tests without `@QuarkusTest`.

---

## Services

| Service | Package | Responsibilities |
|---|---|---|
| `WorkItemService` | `runtime.service` | Create, assign, claim, complete, reject, delegate; enforces status transitions. Overloaded `complete(+rationale, +planRef)` and `reject(+rationale)` pass through to ledger for GDPR Article 22 compliance. |
| `ExpiryCleanupJob` | `runtime.service` | `@Scheduled` — marks expired WorkItems, fires EscalationPolicy |
| `EscalationPolicy` | `runtime.service` | SPI — pluggable: notify, reassign, auto-reject |

---

## Ledger Module

The optional `quarkus-workitems-ledger` module records every WorkItem lifecycle transition as an
immutable `WorkItemLedgerEntry`. It is activated by adding the module to the classpath — the
core extension is completely unchanged whether the module is present or not.

### Dependency on quarkus-ledger (ADR-0001)

`quarkus-workitems-ledger` depends on `io.quarkiverse.ledger:quarkus-ledger` — a domain-agnostic
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

`WorkItemResource` at `/workitems`:

| Endpoint | Transition | Notes |
|---|---|---|
| `POST /` | → PENDING | Create WorkItem |
| `GET /inbox` | — | `?assignee&candidateGroup&candidateUser&status&priority&category&followUp` |
| `GET /` | — | List all (admin) |
| `GET /{id}` | — | Full WorkItem + audit log |
| `PUT /{id}/claim` | PENDING → ASSIGNED | Caller becomes assignee |
| `PUT /{id}/start` | ASSIGNED → IN_PROGRESS | Begin work |
| `PUT /{id}/complete` | IN_PROGRESS → COMPLETED | Body: resolution JSON |
| `PUT /{id}/reject` | ASSIGNED\|IN_PROGRESS → REJECTED | Body: reason |
| `PUT /{id}/delegate?to=Y` | → DELEGATED → PENDING | Sets owner on first delegation |
| `PUT /{id}/release` | ASSIGNED → PENDING | Relinquish to candidate pool |
| `PUT /{id}/suspend` | ASSIGNED\|IN_PROGRESS → SUSPENDED | Body: reason |
| `PUT /{id}/resume` | SUSPENDED → prior state | Restores ASSIGNED or IN_PROGRESS |
| `PUT /{id}/cancel` | any → CANCELLED | Admin; body: reason |

---

## Configuration

`WorkItemsConfig` — `@ConfigMapping(prefix = "quarkus.workitems")`:

| Property | Default | Meaning |
|---|---|---|
| `quarkus.workitems.default-expiry-hours` | 24 | Default completion deadline |
| `quarkus.workitems.default-claim-hours` | 4 | Default claim deadline (0 = no claim deadline) |
| `quarkus.workitems.escalation-policy` | notify | Completion expiry: `notify`, `reassign`, `auto-reject` |
| `quarkus.workitems.claim-escalation-policy` | notify | Claim deadline breach: `notify`, `reassign` |
| `quarkus.workitems.cleanup.expiry-check-seconds` | 60 | Expiry/claim-deadline job interval |

Consuming app owns all datasource config.

---

## Build Roadmap

| Phase | Status | What |
|---|---|---|
| **1 — Core data model** | ✅ Complete | Storage SPI, JPA defaults, InMemory (testing module), WorkItem + AuditEntry entities, Flyway V1+V2+V3, WorkItemService, WorkItemsConfig, WorkItemLabel (MANUAL/INFERRED), LabelVocabulary + LabelDefinition |
| **2 — REST API** | ✅ Complete | WorkItemResource — 13 endpoints, DTOs, exception mappers |
| **3 — Lifecycle engine** | ✅ Complete | ExpiryCleanupJob, ClaimDeadlineJob, EscalationPolicy SPI + 3 implementations |
| **4 — CDI events** | ✅ Complete | WorkItemLifecycleEvent on all transitions; rationale + planRef fields |
| **5 — Quarkus-Flow integration** | ✅ Complete | `quarkus-workitems-flow` — WorkItemsFlow DSL, HumanTaskFlowBridge, Uni<String> suspension |
| **6 — Ledger module** | ✅ Complete | `quarkus-workitems-ledger` — command/event model, hash chain, attestation, EigenTrust; optional, zero core impact |
| **7 — Label-based queues** | ✅ Complete | `quarkus-workitems-queues` — label model (MANUAL/INFERRED), vocabulary (GLOBAL→PERSONAL scopes), filter engine (JEXL/JQ/Lambda, multi-pass propagation, cascade delete via FilterChain), QueueView named queries, soft assignment. See ADR-0002 and `docs/specs/2026-04-15-queues-design.md` |
| **8 — Native image** | ✅ Complete | GraalVM 25 native build, 19 @QuarkusIntegrationTest tests, 0.084s startup |
| **Examples** | ✅ Complete | `quarkus-workitems-examples` (4 scenarios, all ledger capabilities) + `quarkus-workitems-flow-examples` (WorkItemsFlow DSL showcase) |
| **9 — CaseHub integration** | ⏸ Blocked | `quarkus-workitems-casehub` — CaseHub WorkerRegistry adapter (awaiting CaseHub stable API) |
| **10 — Qhorus integration** | ⏸ Blocked | `quarkus-workitems-qhorus` — MCP tools (awaiting Qhorus stable API) |
| **11 — ProvenanceLink** | ⏸ Blocked | Typed PROV-O causal graph — awaiting CaseHub + Qhorus integrations (issue #39) |

---

## Testing Strategy

Two tiers:

**Unit tests** (no Quarkus boot):
- Use `InMemoryWorkItemRepository` from `quarkus-workitems-testing`
- No datasource, no Flyway, instant execution
- Exercise `WorkItemService` logic in isolation

**Integration tests** (`@QuarkusTest`):
- H2 in-memory datasource; Flyway runs V1 migration at boot
- `@TestTransaction` per test method — each test rolls back, no data leakage
- Exercise full stack: REST → Service → JPA → H2
- TDD: write tests first (RED), implement, run to GREEN
