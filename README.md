# Quarkus WorkItems

Quarkus WorkItems is a standalone Quarkiverse extension that manages WorkItems — units of work that require asynchronous resolution, whether by a human or an AI agent — with expiry, delegation, escalation, priority, candidate groups, and a full audit trail. Add one dependency and your application gets a REST API, lifecycle engine, and CDI event stream for WorkItem lifecycle management.

---

## For humans and agents alike

The "human task" framing is accurate but incomplete. WorkItems is a WorkItem lifecycle manager that doesn't care whether the resolver is human or agent. The `assigneeId` is a string — it could be `"alice"`, `"agent-007"`, or any AI agent identity. Nothing in the model enforces humanity.

**Where a human interacts:**
- Calls `PUT /claim`, `PUT /complete` directly or via a dashboard
- Takes minutes to days to respond
- May delegate, reject, or request suspension
- Resolution is a judgment call — structured or unstructured JSON

**Where an agent interacts:**
- An AI agent creates a WorkItem that needs human input, then polls or observes CDI events for completion
- An AI agent can also *resolve* a WorkItem — `assigneeId: "my-agent"` calling `PUT /complete` is fully supported
- `candidateGroups` routing can include `"ai-agents"` alongside `"human-reviewers"` in the same pool

**The real value is the boundary layer between deterministic machines and non-deterministic actors — human or agent:**

- **Expiry and escalation** — if an agent doesn't resolve by `expiresAt`, WorkItems escalates automatically
- **Audit trail** — every action logged regardless of who or what performed it; essential for compliance when agents are in the loop
- **Delegation** — an agent can delegate to a human when it lacks confidence; the chain records every handoff
- **Suspension** — an agent can suspend a WorkItem when blocked, then resume when unblocked
- **CDI events** — every lifecycle transition fires a `WorkItemLifecycleEvent`; any Quarkus bean — or another agent — can react

The meaningful distinction is not human vs agent. It is **synchronous vs asynchronous resolution**. A machine task in Quarkus-Flow executes in milliseconds. A WorkItem waits — minutes, hours, days — and the waiting infrastructure (deadlines, escalation, audit, delegation) is exactly what WorkItems provides.

---

## Why "WorkItem" not "Task"

Three systems in the Quarkus ecosystem use the word "task" to mean different things:

| Term | System | Meaning |
|---|---|---|
| `Task` | CNCF Serverless Workflow / Quarkus-Flow | Machine-executed workflow step — milliseconds, no assignee, no expiry |
| `Task` | CaseHub | CMMN case work unit — assigned to any worker (human or agent) via capabilities |
| `WorkItem` | Quarkus WorkItems | Asynchronous unit of work awaiting resolution — minutes to days, has assignee, expiry, delegation, audit |

**Rule:** A `Task` is controlled by a machine. A `WorkItem` waits for resolution.

---

## Quick Start

### 1. Add the dependency

```xml
<dependency>
  <groupId>io.quarkiverse.workitems</groupId>
  <artifactId>quarkus-workitems</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Configure a datasource

WorkItems manages its own schema (via Flyway) but uses whatever datasource your application provides:

```properties
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost/myapp
quarkus.datasource.username=myuser
quarkus.datasource.password=mypassword
quarkus.flyway.migrate-at-start=true
```

### 3. Use the REST API

```bash
# Create a WorkItem
curl -X POST http://localhost:8080/workitems \
  -H 'Content-Type: application/json' \
  -d '{
    "title": "Review PR #42",
    "assigneeId": "alice",
    "priority": "HIGH",
    "createdBy": "ci-system"
  }'
# → 201 Created, Location: /workitems/{id}

# Claim it (PENDING → ASSIGNED)
curl -X PUT "http://localhost:8080/workitems/{id}/claim?claimant=alice"

# Start work (ASSIGNED → IN_PROGRESS)
curl -X PUT "http://localhost:8080/workitems/{id}/start?actor=alice"

# Complete it (IN_PROGRESS → COMPLETED)
curl -X PUT "http://localhost:8080/workitems/{id}/complete?actor=alice" \
  -H 'Content-Type: application/json' \
  -d '{"resolution": "{\"approved\": true, \"comment\": \"LGTM\"}"}'
```

---

## WorkItem Lifecycle

```
                    ┌─────────────────────────────────────────┐
                    │                PENDING                  │◄─────────────────────┐
                    │     (available for claiming)            │                      │
                    └──────────┬──────────────────────────────┘                      │
                               │ claim                                               │
                               ▼                                                     │
                    ┌─────────────────────┐                                          │
                    │      ASSIGNED       │──── release ──────────────────────────── ┤
                    │  (claimed, not yet  │                                          │
                    │  started)           │──── delegate ─────────────────────────── ┘
                    └──────────┬──────────┘
                               │ start
                               ▼
                    ┌─────────────────────┐
                    │     IN_PROGRESS     │──── delegate ──────────────────────────── ┐
                    │  (being worked)     │                                           │ (PENDING)
                    └────┬───────────┬────┘                                           │
                         │           │                                                │
                    complete       reject                                             │
                         │           │                                                │
                         ▼           ▼                                                │
                    ┌──────┐    ┌────────┐                                            │
                    │COMPL-│    │REJECT- │   ◄── terminal states                     │
                    │ETED  │    │ED      │                                            │
                    └──────┘    └────────┘                                            │
                                                                                      │
    ASSIGNED or IN_PROGRESS ──── suspend ───► SUSPENDED ──── resume ───► (prior) ────┘

    Any non-terminal ──── cancel ───► CANCELLED  (terminal)

    PENDING | ASSIGNED | IN_PROGRESS | SUSPENDED ──── (deadline) ───► EXPIRED ───► ESCALATED
```

**Terminal states:** COMPLETED, REJECTED, CANCELLED, ESCALATED. No transitions out.

**Delegation detail:** On first delegation the actor becomes the `owner`. Subsequent delegates extend the `delegationChain`. The WorkItem returns to PENDING with the new assignee set, delegationState=PENDING.

---

## Key Features

- **Work queues** — `candidateGroups` and `candidateUsers` allow routing WorkItems to pools; any eligible member can claim via `/inbox`.
- **Labels and label-based queues (`quarkus-workitems-queues`)** — WorkItems carry 0..n path-structured labels (e.g. `legal/contracts/nda`). Labels are either `MANUAL` (human-applied) or `INFERRED` (applied and maintained by the filter engine). Filters are JEXL, JQ, or CDI Lambda expressions that fire on every WorkItem lifecycle event. A `QueueView` is a named label-pattern query — any WorkItem carrying a matching label appears in the queue automatically. The queues module is optional; the core extension is unchanged when absent.
- **Delegation with ownership tracking** — the original actor becomes `owner` on first delegation; the full delegation chain is preserved in `delegationChain`.
- **Claim and completion deadlines** — `claimDeadline` and `expiresAt`; both configurable with per-application defaults.
- **Pluggable escalation policies** — `notify`, `reassign`, or `auto-reject` on deadline breach; implement `EscalationPolicy` for custom behaviour (Slack alerts, PagerDuty, etc.).
- **CDI event emission** — `WorkItemLifecycleEvent` fired on every transition; any CDI bean can observe without coupling to WorkItems internals.
- **Quarkus-Flow DSL (`WorkItemsFlow`)** — extend `WorkItemsFlow` instead of `Flow` to get the `workItem()` DSL method alongside `function()`, `agent()`, and other quarkus-flow task types. The `workItem()` builder creates a WorkItem and returns a `Uni<String>` that resolves when a human or agent acts on it via REST.
- **Ledger module (`quarkus-workitems-ledger`)** — optional accountability module. Add it as a dependency and every WorkItem lifecycle transition is recorded in a rich, immutable ledger. The core extension is completely unchanged when the module is absent. Capabilities:
  - **Command/event separation** — each entry records both the intent (`commandType`, e.g. `CompleteWorkItem`) and the observable fact (`eventType`, e.g. `WorkItemCompleted`) for full CQRS auditability
  - **Decision context snapshots** — a JSON snapshot of WorkItem state is captured at every transition, satisfying GDPR Article 22 and EU AI Act Article 12 point-in-time auditability requirements
  - **SHA-256 hash chain** — each entry carries a digest chained to the previous entry's digest (Certificate Transparency pattern), making ledger tampering detectable
  - **Rationale and plan reference** — actors can record the stated basis for their decision (`rationale`) and the policy version that governed it (`planRef`)
  - **Structured evidence capture** — supporting evidence (confidence scores, model outputs, document references) stored as structured JSON per entry; opt-in via config
  - **Provenance linking** — records which external system or entity originated the WorkItem (`sourceEntityId`, `sourceEntityType`, `sourceEntitySystem`); used by Quarkus-Flow, CaseHub, and Qhorus integrations
  - **Actor type tracking** — entries distinguish `HUMAN`, `AGENT`, and `SYSTEM` actors; AI agents are identified by the `agent:` prefix convention on `actorId`
  - **Peer attestations** — any actor (human reviewer, audit agent, automated compliance check) can stamp a formal verdict (`SOUND`, `FLAGGED`, `ENDORSED`, `CHALLENGED`) with a confidence score onto any ledger entry
  - **Causal linking** — `causedByEntryId` links entries to the entry that caused them (e.g. a delegation entry linked back to the preceding resume)
  - **EigenTrust reputation scoring** — a nightly batch computes actor trust scores from ledger history using exponential time-decay weighting; trust scores influence routing suggestions and are queryable via `GET /workitems/actors/{actorId}/trust`
- **Storage SPI** — `WorkItemRepository` and `AuditEntryRepository` interfaces; default JPA (PostgreSQL/H2) implementations, overridable via `@Alternative @Priority(1)`.
- **Native image support** — validated: GraalVM 25 native image, 0.084s startup, 19 `@QuarkusIntegrationTest` tests.

---

## Configuration Reference

All properties are prefixed with `quarkus.workitems`.

| Property | Default | Description |
|---|---|---|
| `quarkus.workitems.default-expiry-hours` | `24` | Hours until `expiresAt` when no explicit value is provided at creation time. |
| `quarkus.workitems.default-claim-hours` | `4` | Hours until `claimDeadline` for unclaimed WorkItems. Set to `0` to disable claim deadlines. |
| `quarkus.workitems.escalation-policy` | `notify` | Applied when `expiresAt` passes without resolution. Values: `notify`, `reassign`, `auto-reject`. |
| `quarkus.workitems.claim-escalation-policy` | `notify` | Applied when `claimDeadline` passes without claiming. Values: `notify`, `reassign`. |
| `quarkus.workitems.cleanup.expiry-check-seconds` | `60` | How often the expiry and claim-deadline job polls for breached deadlines. |

---

## Modules

| Artifact | Purpose |
|---|---|
| `quarkus-workitems` | Core runtime — WorkItem model, JPA storage, REST API, lifecycle engine, CDI events |
| `quarkus-workitems-deployment` | Build-time processor — feature registration, native image config |
| `quarkus-workitems-testing` | `InMemoryWorkItemRepository` + `InMemoryAuditEntryRepository` for unit tests without a datasource |
| `quarkus-workitems-flow` | Quarkus-Flow integration — `WorkItemsFlow` base class, `WorkItemTaskBuilder` DSL, `HumanTaskFlowBridge`, `WorkItemFlowEventListener` |
| `quarkus-workitems-ledger` | Optional accountability module — command/event ledger, SHA-256 hash chain, peer attestation, EigenTrust reputation scoring. Zero impact on the core extension when absent. |
| `quarkus-workitems-queues` | Optional label-based work queues — saved and ad-hoc filters (JEXL, JQ, Lambda CDI), `FilterChain` derivation graph, `QueueView` named label-pattern queries, soft assignment. Zero impact on the core extension when absent. |

Future modules (not yet released): `quarkus-workitems-casehub`, `quarkus-workitems-qhorus`, `quarkus-workitems-mongodb`, `quarkus-workitems-redis`.

---

## Build

```bash
# JVM tests (all modules)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install

# Tests for the runtime module only
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl runtime

# Integration tests (black-box, full stack)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn verify -pl integration-tests

# Native image integration tests
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
  mvn verify -Pnative -pl integration-tests
```

---

## Ecosystem Context

Quarkus WorkItems is part of the Quarkus Native AI Agent Ecosystem, which also includes Quarkus-Flow (workflow execution via CNCF Serverless Workflow), CaseHub (case orchestration), and Qhorus (agent communication mesh). WorkItems has no dependency on any of these — it is the independent human task layer. Optional integration modules (`quarkus-workitems-flow`, `quarkus-workitems-casehub`, `quarkus-workitems-qhorus`) depend on WorkItems, not vice versa, so you can use WorkItems standalone in any Quarkus application.

---

## Documentation

- [**API Reference**](docs/api-reference.md) — all REST endpoints, request/response schemas, status enums, error formats, CDI event types, inbox query parameters, ledger and trust-score endpoints
- [**Integration Guide**](docs/integration-guide.md) — standalone REST, Quarkus-Flow DSL (`WorkItemsFlow`), CDI event observation, custom escalation policies, unit testing without a datasource, ledger module setup
- [**Design Specification**](docs/specs/2026-04-14-tarkus-design.md) — full data model, storage SPI, lifecycle engine, future considerations
- [**Ledger Design**](docs/specs/ledger-design.md) — ledger module architecture: command/event model, hash chain, attestation, EigenTrust reputation
- [**Queues Design**](docs/specs/2026-04-15-queues-design.md) — label model, vocabulary, filter engine, FilterChain, queue views
- [**Implementation Tracker**](docs/DESIGN.md) — component structure, domain model, services, build roadmap

---

## License

Apache 2.0
