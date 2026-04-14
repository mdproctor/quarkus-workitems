# Quarkus Tarkus — Design Document

## Overview

Quarkus Tarkus provides human-scale WorkItem lifecycle management for the Quarkus
ecosystem. Any Quarkus application adds `quarkus-tarkus` as a dependency and gets a
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
| `WorkItem` | Quarkus Tarkus | Human-resolved unit of work — minutes/days, has assignee, expiry, delegation, audit |

**Rule:** A `Task` is controlled by a machine. A `WorkItem` waits for a human.

---

## Component Structure

Maven multi-module layout following Quarkiverse conventions:

| Module | Artifact | Purpose |
|---|---|---|
| Parent | `quarkus-tarkus-parent` | BOM, version management |
| Runtime | `quarkus-tarkus` | Core — WorkItem model, storage SPI, JPA defaults, service, REST API, lifecycle engine |
| Deployment | `quarkus-tarkus-deployment` | Build-time processor — feature registration, native config |
| Testing | `quarkus-tarkus-testing` | `InMemoryWorkItemRepository` — no datasource needed for unit tests |
| *(future)* | `quarkus-tarkus-flow` | Quarkus-Flow `TaskExecutorFactory` SPI integration |
| *(future)* | `quarkus-tarkus-casehub` | CaseHub `WorkerRegistry` adapter |
| *(future)* | `quarkus-tarkus-qhorus` | Qhorus MCP tools |
| *(future)* | `quarkus-tarkus-mongodb` | MongoDB-backed `WorkItemRepository` |
| *(future)* | `quarkus-tarkus-redis` | Redis-backed `WorkItemRepository` |

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
override via `@Alternative @Priority(1)`. The `quarkus-tarkus-testing` module provides
`InMemoryWorkItemRepository` + `InMemoryAuditEntryRepository` — no datasource required,
enabling plain unit tests without `@QuarkusTest`.

---

## Services

| Service | Package | Responsibilities |
|---|---|---|
| `WorkItemService` | `runtime.service` | Create, assign, claim, complete, reject, delegate; enforces status transitions |
| `ExpiryCleanupJob` | `runtime.service` | `@Scheduled` — marks expired WorkItems, fires EscalationPolicy |
| `EscalationPolicy` | `runtime.service` | SPI — pluggable: notify, reassign, auto-reject |

---

## REST API Surface

`WorkItemResource` at `/tarkus/workitems`:

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

`TarkusConfig` — `@ConfigMapping(prefix = "quarkus.tarkus")`:

| Property | Default | Meaning |
|---|---|---|
| `quarkus.tarkus.default-expiry-hours` | 24 | Default completion deadline |
| `quarkus.tarkus.default-claim-hours` | 4 | Default claim deadline (0 = no claim deadline) |
| `quarkus.tarkus.escalation-policy` | notify | Completion expiry: `notify`, `reassign`, `auto-reject` |
| `quarkus.tarkus.claim-escalation-policy` | notify | Claim deadline breach: `notify`, `reassign` |
| `quarkus.tarkus.cleanup.expiry-check-seconds` | 60 | Expiry/claim-deadline job interval |

Consuming app owns all datasource config.

---

## Build Roadmap

| Phase | Status | What |
|---|---|---|
| **1 — Core data model** | ✅ Complete | Storage SPI interfaces + JPA defaults + InMemory (testing module); WorkItem + AuditEntry entities; Flyway V1; WorkItemService; TarkusConfig |
| **2 — REST API** | ⬜ Pending | WorkItemResource — all lifecycle endpoints |
| **3 — Lifecycle engine** | ⬜ Pending | ExpiryCleanupJob, EscalationPolicy SPI + defaults |
| **4 — CloudEvents** | ⬜ Pending | Event emission on all transitions |
| **5 — Quarkus-Flow integration** | ⬜ Pending | `quarkus-tarkus-flow`, TaskExecutorFactory SPI |
| **6 — CaseHub integration** | ⬜ Pending | `quarkus-tarkus-casehub`, WorkerRegistry adapter |
| **7 — Qhorus integration** | ⬜ Pending | `quarkus-tarkus-qhorus`, MCP tools |
| **8 — Native image** | ⬜ Pending | GraalVM native build validation |

---

## Testing Strategy

Two tiers:

**Unit tests** (no Quarkus boot):
- Use `InMemoryWorkItemRepository` from `quarkus-tarkus-testing`
- No datasource, no Flyway, instant execution
- Exercise `WorkItemService` logic in isolation

**Integration tests** (`@QuarkusTest`):
- H2 in-memory datasource; Flyway runs V1 migration at boot
- `@TestTransaction` per test method — each test rolls back, no data leakage
- Exercise full stack: REST → Service → JPA → H2
- TDD: write tests first (RED), implement, run to GREEN
