# Quarkus Tarkus

Quarkus Tarkus is a standalone Quarkiverse extension that gives any Quarkus application a human task inbox. It manages WorkItems — units of work requiring human judgment — with expiry, delegation, escalation, priority, candidate groups, and a full audit trail. Add one dependency and your application gets a REST API, lifecycle engine, and CDI event stream for human task management.

---

## Why "WorkItem" not "Task"

Three systems in the Quarkus ecosystem use the word "task" to mean different things:

| Term | System | Meaning |
|---|---|---|
| `Task` | CNCF Serverless Workflow / Quarkus-Flow | Machine-executed workflow step — milliseconds, no assignee, no expiry |
| `Task` | CaseHub | CMMN case work unit — assigned to any worker (human or agent) via capabilities |
| `WorkItem` | Quarkus Tarkus | Human-resolved unit of work — minutes to days, has assignee, expiry, delegation, audit |

**Rule:** A `Task` is controlled by a machine. A `WorkItem` waits for a human.

---

## Quick Start

### 1. Add the dependency

```xml
<dependency>
  <groupId>io.quarkiverse.tarkus</groupId>
  <artifactId>quarkus-tarkus</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Configure a datasource

Tarkus manages its own schema (via Flyway) but uses whatever datasource your application provides:

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
curl -X POST http://localhost:8080/tarkus/workitems \
  -H 'Content-Type: application/json' \
  -d '{
    "title": "Review PR #42",
    "assigneeId": "alice",
    "priority": "HIGH",
    "createdBy": "ci-system"
  }'
# → 201 Created, Location: /tarkus/workitems/{id}

# Claim it (PENDING → ASSIGNED)
curl -X PUT "http://localhost:8080/tarkus/workitems/{id}/claim?claimant=alice"

# Start work (ASSIGNED → IN_PROGRESS)
curl -X PUT "http://localhost:8080/tarkus/workitems/{id}/start?actor=alice"

# Complete it (IN_PROGRESS → COMPLETED)
curl -X PUT "http://localhost:8080/tarkus/workitems/{id}/complete?actor=alice" \
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
- **Delegation with ownership tracking** — the original actor becomes `owner` on first delegation; the full delegation chain is preserved in `delegationChain`.
- **Claim and completion deadlines** — `claimDeadline` and `expiresAt`; both configurable with per-application defaults.
- **Pluggable escalation policies** — `notify`, `reassign`, or `auto-reject` on deadline breach; implement `EscalationPolicy` for custom behaviour (Slack alerts, PagerDuty, etc.).
- **CDI event emission** — `WorkItemLifecycleEvent` fired on every transition; any CDI bean can observe without coupling to Tarkus internals.
- **Quarkus-Flow integration** — `HumanTaskFlowBridge` suspends a workflow until a human resolves the WorkItem, then resumes with the resolution JSON.
- **Storage SPI** — `WorkItemRepository` and `AuditEntryRepository` interfaces; default JPA (PostgreSQL/H2) implementations, overridable via `@Alternative @Priority(1)`.
- **Native image support** — target: GraalVM 25 native image (validation in Phase 8).

---

## Configuration Reference

All properties are prefixed with `quarkus.tarkus`.

| Property | Default | Description |
|---|---|---|
| `quarkus.tarkus.default-expiry-hours` | `24` | Hours until `expiresAt` when no explicit value is provided at creation time. |
| `quarkus.tarkus.default-claim-hours` | `4` | Hours until `claimDeadline` for unclaimed WorkItems. Set to `0` to disable claim deadlines. |
| `quarkus.tarkus.escalation-policy` | `notify` | Applied when `expiresAt` passes without resolution. Values: `notify`, `reassign`, `auto-reject`. |
| `quarkus.tarkus.claim-escalation-policy` | `notify` | Applied when `claimDeadline` passes without claiming. Values: `notify`, `reassign`. |
| `quarkus.tarkus.cleanup.expiry-check-seconds` | `60` | How often the expiry and claim-deadline job polls for breached deadlines. |

---

## Modules

| Artifact | Purpose |
|---|---|
| `quarkus-tarkus` | Core runtime — WorkItem model, JPA storage, REST API, lifecycle engine, CDI events |
| `quarkus-tarkus-deployment` | Build-time processor — feature registration, native image config |
| `quarkus-tarkus-testing` | `InMemoryWorkItemRepository` + `InMemoryAuditEntryRepository` for unit tests without a datasource |
| `quarkus-tarkus-flow` | Quarkus-Flow integration — `HumanTaskFlowBridge`, `WorkItemFlowEventListener` |

Future modules (not yet released): `quarkus-tarkus-casehub`, `quarkus-tarkus-qhorus`, `quarkus-tarkus-mongodb`, `quarkus-tarkus-redis`.

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

Quarkus Tarkus is part of the Quarkus Native AI Agent Ecosystem, which also includes Quarkus-Flow (workflow execution via CNCF Serverless Workflow), CaseHub (case orchestration), and Qhorus (agent communication mesh). Tarkus has no dependency on any of these — it is the independent human task layer. Optional integration modules (`quarkus-tarkus-flow`, `quarkus-tarkus-casehub`, `quarkus-tarkus-qhorus`) depend on Tarkus, not vice versa, so you can use Tarkus standalone in any Quarkus application.

---

## License

Apache 2.0
