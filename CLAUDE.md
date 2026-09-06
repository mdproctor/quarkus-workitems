# work Workspace

**Name:** casehub-work
**Project repo:** proj/
**Workspace type:** public


## Session Start


## Artifacts

| Artifact | Skill | Path | Destination | Notes |
|---|---|---|---|---|
| specs | brainstorming | `specs/` | project | promoted to `docs/specs/` at epic close |
| plans | writing-plans | `plans/` | workspace | permanent |
| handover | handover | `HANDOFF.md` | workspace | single file, overwritten each session |
| idea-log | idea-log | `IDEAS.md` | workspace | single file |
| design-snapshot | design-snapshot | `snapshots/` | workspace | INDEX.md; auto-pruned, max 10 |
| epic journal | java-update-design | `design/JOURNAL.md` | workspace | written on epic branches |
| adr | adr | `adr/` | project | promoted to `docs/adr/` at epic close |
| blog | write-blog | `~/.claude/blog-routing.yaml` | — | no workspace staging — routed automatically |

## Git Discipline

Two git repositories are active in every session: a **workspace** (methodology artifacts: handover, specs, plans, ADRs — blog is not staged here, goes directly to destination via blog-routing.yaml) and the **project repo** (source code).

Before any git operation, run `git rev-parse --show-toplevel` to confirm which repo is currently active. Do not assume — the session may have opened in either. cd to the correct repo before staging:
- Source code commits → project repo
- Methodology artifacts → workspace


## Rules

- All methodology artifacts go here, not in the project repo
- Promotion to project repo is always explicit — never automatic
- Workspace branches mirror project branches — switch both together

---

# CaseHub Work — Claude Code Project Guide

## Platform Docs
- [Platform Index](https://raw.githubusercontent.com/casehubio/parent/main/docs/INDEX.md) — discovery index (start here)
- [Building Platform](https://raw.githubusercontent.com/casehubio/parent/main/docs/guides/building-platform.md) — platform contributor guide

## Repo Guide

This repo owns its own documentation, synced to parent via CI:
- `docs/guides/consumer-guide.md` — for app builders: modules, APIs, quick start
- `docs/guides/contributor-guide.md` — for platform builders: architecture, SPIs, internals

Update the relevant guide in the same session when implementation changes modules, SPIs, or public APIs. Do not defer — drift compounds.

Read `docs/guides/consumer-guide.md` for app-level work. Only read `docs/guides/contributor-guide.md` when modifying this repo's internals or extension points.

## Reference Documents (casehub-parent)

| Document | What it covers |
|----------|---------------|
| `../garden/docs/protocols/casehub/FOUNDATION-INDEX.md` | CaseHub foundation protocols |

---

## Project Type

type: java

**Stack:** Java 21 (on Java 26 JVM), Quarkus 3.32.2, GraalVM 25 (native image target)

---

## What This Project Is

CaseHub Work is a **CaseHub platform module** providing **human-scale WorkItem lifecycle management**. It gives any Quarkus application a human task inbox with expiry, delegation, escalation, priority, and audit trail — usable independently or with an optional Quarkus-Flow integration. CaseHub engine adapter (`casehub-work-engine-adapter`) and Qhorus bridge (`casehub-work-qhorus`) are shipped. It is hosted under the CaseHub organisation (`casehubio/work`), not submitted to Quarkiverse.

**The core concept — WorkItem (not Task):**
A `WorkItemEntity` is a unit of work requiring human attention or judgment. It is deliberately NOT called `Task` because:
- The CNCF Serverless Workflow SDK (used by Quarkus-Flow) has its own `Task` class (`io.serverlessworkflow.api.types.Task`) — a machine-executed workflow step
- CaseHub has its own `Task` class — a CMMN-style case work unit
Using `WorkItemEntity` avoids naming conflicts and accurately describes what WorkItems manages: work that waits for a person.

**See the full glossary:** `ARC42STORIES.MD §13` — Glossary

---

## Naming

| Element | Value |
|---|---|
| GitHub repo | `casehubio/work` |
| groupId | `io.casehub` |
| Parent artifactId | `casehub-work-parent` |
| Runtime artifactId | `casehub-work` |
| Deployment artifactId | `casehub-work-deployment` |
| Root Java package | `io.casehub.work` |
| Runtime subpackage | `io.casehub.work.runtime` |
| Deployment subpackage | `io.casehub.work.deployment` |
| Config prefix | `casehub.work` |
| Feature name | `workitems` |
| Version | `0.2-SNAPSHOT` (published to GitHub Packages under casehubio org) |

---

## Ecosystem Context

WorkItems is the independent human task layer below CaseHub, Quarkus-Flow, and Qhorus — no dependency on any of them. Integration modules depend on WorkItems, not vice versa.

**Related projects (read only, for context):**
- `~/claude/casehub/qhorus` — agent communication mesh (Qhorus integration target)
- `~/claude/casehub/engine` — real CaseHub engine (CMMN + blackboard; **not** `~/claude/casehub-poc` which is the retiring POC)
- `~/dev/quarkus-flow` — workflow engine (Quarkus-Flow integration target; uses CNCF Serverless Workflow SDK)
- `~/claude/casehub/claudony` — integration layer; will surface WorkItems inbox in its dashboard

---

## Project Structure

Module ownership and structural constraints: `docs/MODULES.md` — read before any multi-module work.

---

## Build and Test

```bash
# Build all modules
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install

# Test a specific module (prefer scripts/ for timeouts)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=ClassName -pl runtime

# Black-box integration tests (JVM mode)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn verify -pl integration-tests

# Native image integration tests (requires GraalVM 25)
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home mvn verify -Pnative -pl integration-tests
```

**Use `mvn` not `./mvnw`** — maven wrapper not configured on this machine.

---

## Build Discipline

**Never run `mvn install` or `mvn test` without `-pl <module>`.** The full project has 20+ modules; a full build times out in any AI tool context window. Always target the specific module you changed.

**Use `scripts/` helper scripts** — they enforce hard timeouts. See `scripts/README.md` for script reference and expected test times per module.

**Before writing any Java code:** read `docs/GOTCHAS.md` — categorised gotchas for extension build, Hibernate/JPA, CDI/transactions, testing, domain behaviour, and cross-repo issues.

**When adding Flyway migrations:** read `docs/FLYWAY.md` — version range ownership, V-number reservation procedure for concurrent epic branches, and casehub-ledger prerequisites.

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

`ARC42STORIES.MD` is the primary architecture record — §5 Building Block View (module structure, domain model), §9 Journeys and Chapters (35-chapter delivery history, 7 layer entries with key files, wiring, gotchas, and patterns), §10 Architectural Decisions (cross-references to `docs/adr/`).

REST API reference: [`docs/api-reference.md`](docs/api-reference.md)
Configuration properties: [`README.md`](README.md#configuration)

## engine-adapter Module

CaseHub engine adapter (`casehub-work-engine-adapter`, package `io.casehub.work.engine`). Bridges casehub-work WorkItem lifecycle with CaseHub engine planning PlanItem lifecycle. Relocated from `casehub-engine-work-adapter` in the engine repo (Refs #290).

Activated by adding `casehub-work-engine-adapter` to the consumer's classpath (transitively brings `casehub-engine-planning`). Required for any runtime that uses `humanTask` or action gate YAML bindings. `HumanTaskScheduleHandler` implements `HumanTaskScheduler` SPI; `ActionGateWorkItemHandler` implements `ActionGateScheduler` SPI. Both are discovered by the engine runtime via CDI `Instance<>` — no Vert.x event bus.

**Routing context threading (engine#756):** `HumanTaskScheduleHandler` serializes `candidateScores` (`Map<String, Double>`) and `experiences` (`List<RetrievedExperience>`) from `HumanTaskScheduleRequest` to JSON strings and passes them through `WorkItemCreateRequest` → `WorkItemService` → `WorkItemEntity` entity (TEXT columns, V44 migration) → REST API responses. Both inline and template modes thread the data. `WorkItemTemplateService.mergeRequestWithTemplate()` passes both fields through unchanged. The work repo treats both as opaque JSON — no engine type dependencies in work-api or work-runtime.

Two-way bridge:
- **Outbound** (`HumanTaskScheduleHandler`, `ActionGateWorkItemHandler`) — implements engine SPIs (`HumanTaskScheduler`, `ActionGateScheduler`), creates WorkItems
- **Inbound** (`WorkItemLifecycleAdapter`) — observes `WorkItemEvent`, translates terminal events to PlanItem transitions via `PlanItemCompletionApplier`

`callerRef` format: `case:{caseId}/pi:{planItemId}` (HumanTask) or `case:{caseId}/gate:{gateId}` (ActionGate). Use `CallerRef.parse()` / `PlanItemCallerRef.encode()` / `GateCallerRef.encode()`.

**Inbound work item creation (engine#974):** `InboundWorkItemSchedulerImpl` implements `InboundWorkItemScheduler` SPI (engine-common). Converts `InboundWorkItemRequest` to `WorkItemCreateRequest`, wraps creation in `TenantContextExecutor.runInTenantContext()` (request arrives outside request scope from qhorus afterCompletion callback). Overrides engine's `NoOpInboundWorkItemScheduler` (`@DefaultBean`) when engine-adapter is on the classpath. Priority mapping: String → `WorkItemPriority.valueOf()`, null-safe, fail-fast on invalid values.

**Actor state contribution (engine#974):** `WorkActorStateContributor` implements `ActorStateContributor` (platform-api). Queries `WorkItemStore` for active items (ASSIGNED, IN_PROGRESS, SUSPENDED) by `assigneeId`. Extracts `caseId` from `callerRef` via `CallerRef.parse()` (same module — handles both PlanItemRef and GateRef formats). `sourceName()` returns `"work"`. Relocated from `casehub-engine-actor-state`.

---

## qhorus Module

Qhorus bridge (`casehub-work-qhorus`, package `io.casehub.work.qhorus`). Bridges casehub-work WorkItem lifecycle with Qhorus agent mesh channels.

Activated by adding `casehub-work-qhorus` to the consumer's classpath. Requires both `casehub-work` and `casehub-qhorus` at runtime.

Three MCP tools: `request_human_work` (creates WorkItem + posts oversight/QUERY), `check_work_status` (polls status), `wait_for_work` (polls until terminal).

Outbound: `QhorusWorkItemLifecycleAdapter` implements `WorkItemObserver`, posts terminal events (COMPLETED→DONE, REJECTED→FAILURE, CANCELLED→DECLINE) back to the originating Qhorus channel. CallerRef format: `qhorus:{channelId}/{messageId}/{correlationId}`.

---

## annotations Module

Annotation-driven human-in-the-loop model (`casehub-work-annotations`, package `io.casehub.work.annotations`). Part of the blocks#115 annotation-driven agent programming model epic (#356).

Four composable annotations reduce 15-30 lines of programmatic WorkItem configuration to 1-4 annotations:

| Annotation | Purpose | Maps to |
|---|---|---|
| `@HumanApproval` | Per-WorkItem configuration | `WorkItemCreateRequest` |
| `@RequiresQuorum` | Multi-instance coordination | `MultiInstanceConfig` |
| `@Escalate` | Escalation policy | `SlaBreachPolicy` SPI |
| `@SkillMatch` | Routing strategy selection | `WorkerSelectionStrategy` SPI |

**Dual-path execution:** Standalone path generates CDI interceptors via Gizmo at build time (any Quarkus app). Blocks composition path produces `WorkAnnotationsProcessedBuildItem` for blocks-engine-adapter to wire governance interceptors on `@Worker`/`@*Agent` methods.

**Module structure:** Follows engine-annotations pattern (parent → runtime + deployment). Runtime defines `@interface`s and descriptor records. Deployment provides `WorkAnnotationsProcessor` `@BuildStep` (scan, validate, produce build items).

---

## Project Artifacts

Paths that are project content (not workspace noise). Skills use this to avoid
filtering or dropping commits that touch these paths.

| Path | What it is |
|------|------------|
| `CLAUDE.md` | Project conventions (build, test, naming) |
| `docs/adr/` | Architecture decision records |
| `ARC42STORIES.MD` | Primary architecture record |
| `docs/MODULES.md` | Module ownership and structural constraints |
| `docs/ECOSYSTEM.md` | Cross-project conventions (packaging, versioning, fork workflow) |
| `docs/protocols/` | Standing architectural rules (multi-tenancy conventions, store patterns) |

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** casehubio/work

**Active epics:**

| # | Epic | Status | Notes |
|---|---|---|---|
| #92 | Distributed WorkItems — clustering + federation | in progress | Single-cluster done; #332 coordinated rollback is the sole remaining child |
| #39 | ProvenanceLink (PROV-O causal graph) | deferred | Awaiting production validation of simpler causal model |

**Automatic behaviours (Claude follows these at all times in this project):**
- **Before implementation begins** — check if an active issue exists. If not, run issue-workflow Phase 1 before writing any code. Create a child issue under the matching epic above.
- **Before any commit** — run issue-workflow Phase 3 to confirm issue linkage.
- **All commits should reference an issue** — `Refs #N` (ongoing) or `Closes #N` (done). Also reference the parent epic: `Refs #77` etc.
- **Code review fix commits** — when committing fixes found during a code review, create or reuse an issue for that review work **before** committing. Use `Refs #N` on the relevant epic even if it is already closed.
- **New feature requests** — assess which epic it belongs to before creating the issue. If none fits, propose a new epic first.

## Ecosystem Conventions

Cross-project conventions (GitHub Packages setup, SNAPSHOT versioning, fork workflow): `docs/ECOSYSTEM.md`.

