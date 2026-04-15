# Quarkus Tarkus — Claude Code Project Guide

## Project Type

type: java

**Stack:** Java 21 (on Java 26 JVM), Quarkus 3.32.2, GraalVM 25 (native image target)

---

## What This Project Is

Quarkus Tarkus is a standalone Quarkiverse extension providing **human-scale WorkItem lifecycle management**. It gives any Quarkus application a human task inbox with expiry, delegation, escalation, priority, and audit trail — usable independently or with optional integrations for Quarkus-Flow, CaseHub, and Qhorus.

**The core concept — WorkItem (not Task):**
A `WorkItem` is a unit of work requiring human attention or judgment. It is deliberately NOT called `Task` because:
- The CNCF Serverless Workflow SDK (used by Quarkus-Flow) has its own `Task` class (`io.serverlessworkflow.api.types.Task`) — a machine-executed workflow step
- CaseHub has its own `Task` class — a CMMN-style case work unit
Using `WorkItem` avoids naming conflicts and accurately describes what Tarkus manages: work that waits for a person.

**See the full glossary:** `docs/DESIGN.md` § Glossary

---

## Quarkiverse Naming

| Element | Value |
|---|---|
| GitHub repo | `mdproctor/quarkus-tarkus` (→ `quarkiverse/quarkus-tarkus` when submitted) |
| groupId | `io.quarkiverse.tarkus` |
| Parent artifactId | `quarkus-tarkus-parent` |
| Runtime artifactId | `quarkus-tarkus` |
| Deployment artifactId | `quarkus-tarkus-deployment` |
| Root Java package | `io.quarkiverse.tarkus` |
| Runtime subpackage | `io.quarkiverse.tarkus.runtime` |
| Deployment subpackage | `io.quarkiverse.tarkus.deployment` |
| Config prefix | `quarkus.tarkus` |
| Feature name | `tarkus` |

---

## Ecosystem Context

Tarkus is part of the Quarkus Native AI Agent Ecosystem:

```
CaseHub (case orchestration)   Quarkus-Flow (workflow execution)   Qhorus (agent mesh)
         │                              │                               │
         └──────────────────────────────┼───────────────────────────────┘
                                        │
                              Quarkus Tarkus (WorkItem inbox)
                                        │
                              quarkus-tarkus-casehub   (optional adapter)
                              quarkus-tarkus-flow      (optional adapter)
                              quarkus-tarkus-qhorus    (optional adapter)
```

Tarkus has **no dependency on CaseHub, Quarkus-Flow, or Qhorus** — it is the independent human task layer. The integration modules (future) depend on Tarkus, not vice versa.

**Related projects (read only, for context):**
- `~/claude/quarkus-qhorus` — agent communication mesh (Qhorus integration target)
- `~/claude/casehub` — case orchestration engine (CaseHub integration target)
- `~/dev/quarkus-flow` — workflow engine (Quarkus-Flow integration target; uses CNCF Serverless Workflow SDK)
- `~/claude/claudony` — integration layer; will surface Tarkus inbox in its dashboard

---

## Project Structure

```
quarkus-tarkus/
├── runtime/                               — Extension runtime module
│   └── src/main/java/io/quarkiverse/tarkus/runtime/
│       ├── config/TarkusConfig.java       — @ConfigMapping(prefix = "quarkus.tarkus")
│       ├── model/
│       │   ├── WorkItem.java              — PanacheEntity (the core concept)
│       │   ├── WorkItemStatus.java        — enum: PENDING|ASSIGNED|IN_PROGRESS|...
│       │   ├── WorkItemPriority.java      — enum: LOW|NORMAL|HIGH|CRITICAL
│       │   └── AuditEntry.java            — PanacheEntity (append-only audit log)
│       ├── repository/
│       │   ├── WorkItemRepository.java    — SPI: save, findById, findAll, findInbox, findExpired
│       │   ├── AuditEntryRepository.java  — SPI: append, findByWorkItemId
│       │   └── jpa/
│       │       ├── JpaWorkItemRepository.java    — default Panache impl (@ApplicationScoped)
│       │       └── JpaAuditEntryRepository.java  — default Panache impl (@ApplicationScoped)
│       ├── service/
│       │   ├── WorkItemService.java       — lifecycle management, expiry, delegation
│       │   └── EscalationPolicy.java      — SPI interface for escalation strategies
│       └── api/
│           └── WorkItemResource.java      — REST API at /tarkus/workitems
├── deployment/                            — Extension deployment (build-time) module
│   └── src/main/java/io/quarkiverse/tarkus/deployment/
│       └── TarkusProcessor.java           — @BuildStep: FeatureBuildItem
├── testing/                               — Test utilities module (quarkus-tarkus-testing)
│   └── src/main/java/io/quarkiverse/tarkus/testing/
│       ├── InMemoryWorkItemRepository.java    — ConcurrentHashMap-backed, no datasource needed
│       └── InMemoryAuditEntryRepository.java  — list-backed
├── docs/
│   ├── DESIGN.md                          — Implementation-tracking design document
│   └── specs/
│       └── 2026-04-14-tarkus-design.md   — Primary design specification
└── HANDOFF.md                             — Session context for resumption
```

**Integration modules (built):**
- `tarkus-flow/` — Quarkus-Flow CDI bridge (`HumanTaskFlowBridge`, `PendingWorkItemRegistry`, `WorkItemFlowEventListener`)
- `integration-tests/` — `@QuarkusIntegrationTest` suite and native image validation (19 tests, 0.084s native startup)

**Future integration modules (not yet scaffolded):**
- `tarkus-casehub/` — CaseHub `WorkerRegistry` adapter (blocked: CaseHub not yet complete)
- `tarkus-qhorus/` — Qhorus MCP tools (`request_approval`, `check_approval`, `wait_for_approval`) (blocked: Qhorus not yet complete)
- `tarkus-mongodb/` — MongoDB-backed `WorkItemRepository`
- `tarkus-redis/` — Redis-backed `WorkItemRepository`

---

## Build and Test

```bash
# Build all modules
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install

# Run tests (runtime module)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl runtime

# Run tests (ledger module)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl quarkus-tarkus-ledger

# Run specific test
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=ClassName -pl runtime

# Black-box integration tests (JVM mode)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn verify -pl integration-tests

# Native image integration tests (requires GraalVM 25)
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
  mvn verify -Pnative -pl integration-tests
```

**Use `mvn` not `./mvnw`** — maven wrapper not configured on this machine.

**Quarkiverse format check:** CI runs `mvn -Dno-format` to skip the enforced formatter. Run `mvn` locally to apply formatting.

**Known Quarkiverse gotchas (from quarkus-qhorus experience):**
- `quarkus-extension-processor` requires **Javadoc on every method** in `@ConfigMapping` interfaces, including group accessors — missing one causes a compile-time error
- The `extension-descriptor` goal validates that the deployment POM declares **all transitive deployment JARs** — run `mvn install -DskipTests` first after modifying the deployment POM
- `key` is a reserved word in H2 — avoid it as a column name in Flyway migrations
- `@QuarkusIntegrationTest` must live in a **separate module** from the extension runtime — the `quarkus-maven-plugin` build goal requires a configured datasource at augmentation time; extensions intentionally omit datasource config (use the `integration-tests/` module)
- `@Scheduled` intervals require `${property}s` syntax (MicroProfile Config), **not** `{property}s` — bare braces are silently ignored at augmentation time, causing `DateTimeParseException` at native startup
- Panache `find()` short-form WHERE clause must use **bare field names** (`assigneeId = :x`), not alias-prefixed names (`wi.assigneeId = :x`) — the alias is internal to Panache and not exposed in the condition string
- `quarkus.http.test-port=0` in test `application.properties` — add when a module has multiple `@QuarkusTest` classes; prevents intermittent `TIME_WAIT` port conflicts when Quarkus restarts between test classes
- `@TestTransaction` + REST assertions don't mix — a `@Transactional` CDI method called from within `@TestTransaction` joins the test transaction; subsequent HTTP calls run in their own transaction and cannot see the uncommitted data (returns 404). Remove `@TestTransaction` from test classes that mix direct service calls with REST Assured assertions

---

## Java and GraalVM on This Machine

```bash
# Java 26 (Oracle, system default) — use for dev and tests
JAVA_HOME=$(/usr/libexec/java_home -v 26)

# GraalVM 25 — use for native image builds only
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home
```

---

## Design Document

`docs/specs/2026-04-14-tarkus-design.md` is the primary design specification.
`docs/DESIGN.md` is the implementation-tracking document (updated as phases complete).

---

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** mdproctor/quarkus-tarkus

**Automatic behaviours (Claude follows these at all times in this project):**
- **Before implementation begins** — check if an active issue exists. If not, run issue-workflow Phase 1 before writing any code.
- **Before any commit** — run issue-workflow Phase 3 to confirm issue linkage.
- **All commits should reference an issue** — `Refs #N` (ongoing) or `Closes #N` (done).
- **Code review fix commits** — when committing fixes found during a code review, create or reuse an issue for that review work **before** committing. Use `Refs #N` on the relevant epic even if it is already closed.
