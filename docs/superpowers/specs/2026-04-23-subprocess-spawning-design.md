# Subprocess Spawning — Design Spec
**Date:** 2026-04-23  
**Status:** DRAFT v2 — reviewed against real casehub-engine + quarkus-ledger  
**Epic:** #105

---

## What this document is

The design for subprocess spawning in quarkus-work, positioning it as the mechanical primitive layer that CaseHub (blackboard + CMMN) and standalone callers drive. Reviewed twice against the real `casehub-engine` at `/Users/mdproctor/dev/casehub-engine` and `quarkus-ledger`. Open questions are explicitly called out where a CaseHub architectural decision is still needed.

---

## Positioning

### quarkus-work is the primitive layer

quarkus-work provides WorkItems — units of human work with lifecycle, assignment, audit, and SLA. It does not orchestrate between WorkItems. It does not decide when to create them or what their completion means to a larger process.

### CaseHub is the orchestration layer

CaseHub (blackboard + CMMN) owns case state, `CasePlanModel`, `PlanItem` lifecycle, `Stage` autocomplete, and activation conditions. It decides *when* to spawn children, *which* children, and *what a child's completion means* for the case. quarkus-work does not replicate any of this.

### The boundary

quarkus-work subprocess spawning:

- Creates child WorkItems from templates on explicit request
- Wires them to a parent via `PART_OF`
- Stores an opaque `callerRef` on each child (CaseHub embeds its routing key here)
- Fires a per-child `WorkItemLifecycleEvent` on every terminal state transition
- Optionally tracks the group and fires a `SpawnGroupCompletedEvent` for standalone callers

quarkus-work does **not**:

- Decide when to spawn
- Apply activation conditions to individual children
- Know what a child group completing means for the overall case
- Implement milestones, ad-hoc fragments, or case lifecycle patterns
- Own completion policy for CaseHub deployments — `completionPolicy` is CaseHub's `Stage.requiredItemIds` + autocomplete, not quarkus-work's concern

---

## Correlation naming: `callerRef`

**`correlationKey` must not be used.** In `casehub-engine`, `correlationKey` is a SHA-256 hash of `workerName:capabilityName:inputDataHash` used by `WorkerExecutionKeys` and `PendingWorkRegistry` for worker-execution idempotency. Reusing that name in quarkus-work would cause confusion across both codebases.

**`callerRef`** is an opaque string field added to:
- Each child entry in the spawn request body
- The `WorkItem` entity (nullable column `caller_ref`)
- Every `WorkItemLifecycleEvent` fired on a child WorkItem

quarkus-work treats `callerRef` as opaque bytes — it stores and echoes it, never interprets it. CaseHub embeds `caseId:planItemId` (or equivalent routing key). When a child completes, CaseHub's adapter uses `callerRef` to locate the right `CasePlanModel` and mark the `PlanItem` COMPLETED without a query.

This mirrors `CaseInstance.parentPlanItemId` in casehub-engine — the existing field explicitly designed for linking a spawned child back to the `PlanItem` that caused it.

---

## What quarkus-work provides

### 1. Caller-driven spawn API

```
POST /workitems/{parentId}/spawn
```

Request body:

```json
{
  "idempotencyKey": "caseid-stageid-attempt-1",
  "completionPolicy": null,
  "children": [
    {
      "templateId": "credit-check-template",
      "callerRef": "case:loan-123/planitem:credit-stage-pi-1"
    },
    {
      "templateId": "fraud-check-template",
      "overrides": { "candidateGroups": "fraud-team" },
      "callerRef": "case:loan-123/planitem:fraud-stage-pi-2"
    },
    {
      "templateId": "compliance-check-template",
      "callerRef": "case:loan-123/planitem:compliance-stage-pi-3"
    }
  ]
}
```

Fields:
- `idempotencyKey` — caller-supplied deduplication key. Separate from `callerRef`. Prevents double-spawn on Quartz job retry or network retry. quarkus-work rejects (409) a second call with the same `idempotencyKey` on the same parent.
- `completionPolicy` — nullable. When null (CaseHub always sends null), quarkus-work fires per-child lifecycle events only; no group-level rollup. When set (`ALL_MUST_COMPLETE` | `M_OF_N`), quarkus-work also tracks the group and fires `SpawnGroupCompletedEvent`. Standalone deployments use this; CaseHub does not.
- `children[].callerRef` — opaque string stored on the spawned WorkItem, echoed in all lifecycle events.
- `children[].overrides` — fields that override the template defaults for this specific child.

Response:

```json
{
  "spawnGroupId": "uuid",
  "children": [
    { "workItemId": "uuid", "callerRef": "case:loan-123/planitem:credit-stage-pi-1" }
  ]
}
```

### 2. Per-child lifecycle events (primary signal for CaseHub)

Every terminal state transition on a child WorkItem fires `WorkItemLifecycleEvent` (already exists). No changes needed to the event structure itself — but `callerRef` is now a field on `WorkItem`, so it is accessible from the event's source object.

CaseHub's adapter (`quarkus-work-casehub`, future module) observes `WorkItemLifecycleEvent` and, for terminal states (COMPLETED, REJECTED, CANCELLED, EXPIRED, ESCALATED), extracts `callerRef` from the WorkItem and routes to the right `CasePlanModel`/`PlanItem`.

Terminal state mapping for CaseHub (mirrors `SubCaseCompletionStrategy`):

| WorkItemStatus | CaseHub PlanItemStatus |
|---|---|
| COMPLETED | COMPLETED |
| REJECTED | FAULTED |
| CANCELLED | CANCELLED |
| EXPIRED | FAULTED |
| ESCALATED | FAULTED |

The resolution payload (`WorkItem.resolution`) must be retrievable when a child completes — CaseHub's adapter fetches `GET /workitems/{id}` to extract `resolution` and write it to `CaseContext`. The lifecycle event itself does not need to carry the full resolution, but must carry enough to identify the WorkItem.

### 3. Template-level spawn config (standalone only)

For applications that do not use CaseHub, `WorkItemTemplate` can embed a spawn config:

```json
{
  "name": "loan-application",
  "spawnConfig": {
    "triggerOn": "ASSIGNED",
    "completionPolicy": "ALL_MUST_COMPLETE",
    "children": [
      { "templateId": "credit-check-template" },
      { "templateId": "fraud-check-template" },
      { "templateId": "compliance-check-template" }
    ]
  }
}
```

**CaseHub never uses template-driven auto-spawn.** CaseHub's `BlackboardPlanConfigurer` and binding/trigger model control when children are spawned — the engine always calls the spawn API explicitly. Template auto-spawn would create a race condition where the engine dispatches a WorkItem but quarkus-work silently spawns additional children the engine knows nothing about.

Config opt-out for CaseHub deployments:
```
quarkus.work.spawn.template-auto-spawn=false   # CaseHub: set to false
quarkus.work.spawn.auto-complete-parent=false  # CaseHub: set to false
```

### 4. Spawn group entity (standalone only; internal for CaseHub)

`WorkItemSpawnGroup` tracks children created together, used for group-level completion rollup in standalone deployments. CaseHub does not use this entity — CaseHub's `BlackboardRegistry`/`CasePlanModel` is the authoritative group state.

```
WorkItemSpawnGroup {
  id:                  UUID
  parentId:            UUID
  idempotencyKey:      String (unique per parent)
  childIds:            UUID[] (JSON column or join table)
  completionPolicy:    ALL_MUST_COMPLETE | M_OF_N | NONE
  completionThreshold: int   (for M_OF_N)
  status:              ACTIVE | COMPLETE | CANCELLED
  createdAt:           Instant
  completedAt:         Instant?
}
```

### 5. SpawnGroupCompletedEvent (standalone convenience)

Fired only when `completionPolicy` is non-null and the threshold is met:

```java
public class SpawnGroupCompletedEvent {
    UUID spawnGroupId;
    UUID parentWorkItemId;
    List<ChildOutcome> childOutcomes;  // id + status + callerRef per child
    CompletionPolicy policy;
}
```

CaseHub does not observe this event. CaseHub works from per-child `WorkItemLifecycleEvent`.

### 6. WorkEventType.SPAWNED (new)

Add `SPAWNED` to `WorkEventType` enum in `quarkus-work-api`. This allows:
- `FilterRegistryEngine` to react to spawn events
- Ledger module to record the spawn act as a distinct entry
- `EscalationPolicy` observers to see when a group is spawned

### 7. Cascade cancellation

```
DELETE /workitems/{parentId}/spawn-groups/{groupId}?cancelChildren=true
```

Default `cancelChildren=false` (group marked CANCELLED, children unchanged). With `cancelChildren=true`, all PENDING children are cancelled atomically. CaseHub uses `cancelChildren=true` when `StageTerminatedEvent` fires — avoids O(N) individual cancel calls.

---

## Ledger integration

### causedByEntryId wiring

When children are spawned, each child's CREATED `LedgerEntry` must carry `causedByEntryId` pointing to the parent WorkItem's triggering ledger entry (the entry for the state that caused the spawn — e.g., ASSIGNED or SPAWNED). This wires the causal chain in the PROV-DM graph: `prov:wasDerivedFrom` links child to parent spawn event.

This is not automatic — `LedgerEventCapture` must receive the parent entry UUID at spawn time and set `causedByEntryId` on each child's CREATED entry.

### WorkEventType.SPAWNED in ledger

The SPAWNED event on the parent WorkItem should produce its own `LedgerEntry` with `subjectId = parentWorkItemId`. Child CREATED entries then carry `causedByEntryId = parentSpawnEntryId`, creating the two-hop chain: parent SPAWNED → child CREATED.

---

## Orchestration vs choreography — open CaseHub decision

The real casehub-engine has two worker execution paths:

**Choreography**: binding fires → `WorkBroker` dispatches → CDI events signal completion → `CaseContextChangedEvent` → engine re-evaluates. No future/suspension.

**Orchestration**: `WorkOrchestrator.submitAndWait()` → `PendingWorkRegistry` registers a `CompletableFuture` keyed on `correlationKey` → `CaseInstance` transitions to `WAITING` with `waitingForWorkId` set → on `WORK_COMPLETED`, future resolves and case resumes.

**For WorkItems, CaseHub must choose which path.** CDI events (per-child `WorkItemLifecycleEvent`) are sufficient for choreography mode. For orchestration mode, the `PendingWorkRegistry` must be keyed on something quarkus-work can signal — `callerRef` is the natural candidate (CaseHub embeds a key that it also registers in `PendingWorkRegistry`). quarkus-work does not need to know which mode CaseHub is using; it just echoes `callerRef` on every lifecycle event. CaseHub's adapter decides whether to route that to `PendingWorkRegistry.complete()` or to `CaseContextChangedEvent`.

**This is an open CaseHub architectural decision.** quarkus-work's `callerRef` mechanism supports both paths without change.

---

## REST API summary

| Method | Path | Description |
|---|---|---|
| `POST` | `/workitems/{id}/spawn` | Spawn children, create PART_OF links, return group + child IDs |
| `GET` | `/workitems/{id}/spawn-groups` | List spawn groups for a parent (standalone use) |
| `GET` | `/spawn-groups/{id}` | Get group status and child outcomes (standalone use) |
| `DELETE` | `/workitems/{id}/spawn-groups/{groupId}` | Cancel group; `?cancelChildren=true` cascades |
| `GET` | `/workitems/{id}/children` | (existing) PART_OF children — unchanged |

---

## Test strategy

Every layer is independently testable. Tests are organised by concern.

### Unit tests (no Quarkus, no DB)

**SpawnRequestBuilder:**
- Template override merging: child overrides win over template defaults field-by-field
- `callerRef` is preserved exactly as provided — no transformation
- Null template → `WorkItemCreateException` before any persistence

**SpawnEngine:**
- Happy path: parent + spawn config → correct `WorkItemCreateRequest` objects produced
- `triggerOn=ASSIGNED`: only fires on ASSIGNED transition, not CREATED or IN_PROGRESS
- Idempotency: same `idempotencyKey` on same parent → returns existing group, no second spawn
- No `spawnConfig` on template → no-op, no exception
- `completionPolicy=null` → no `SpawnGroup` created, no rollup

**CompletionRollupService (standalone path only):**
- `ALL_MUST_COMPLETE`: fires `SpawnGroupCompletedEvent` only when last REQUIRED child completes
- `M_OF_N`: fires at threshold, not before; subsequent completions beyond threshold are no-ops
- `NONE`: never fires rollup
- CANCELLED child: does not count toward completion; does not block if policy is M_OF_N
- REJECTED child: blocks `ALL_MUST_COMPLETE`; counts toward M_OF_N threshold
- EXPIRED child: treated same as REJECTED
- Two children completing simultaneously: exactly one `SpawnGroupCompletedEvent` fires (optimistic lock on `SpawnGroup`)

**SpawnGroup state machine:**
- `ACTIVE → COMPLETE`: only when policy threshold met
- `ACTIVE → CANCELLED`: cascades to set group CANCELLED; children not touched
- Already-COMPLETE: subsequent child terminal events are no-ops

**WorkItemStatus → CaseHub PlanItemStatus mapping:**
- COMPLETED → COMPLETED
- REJECTED → FAULTED
- CANCELLED → CANCELLED
- EXPIRED → FAULTED
- ESCALATED → FAULTED

### Integration tests (@QuarkusTest, H2)

**Spawn API — happy paths:**
- `POST /workitems/{id}/spawn` with valid templateIds → 201, children PENDING, PART_OF links exist, `callerRef` stored on each child
- `completionPolicy=null` → no `SpawnGroup` row created
- `completionPolicy=ALL_MUST_COMPLETE` → `SpawnGroup` row created with status ACTIVE
- `callerRef` round-trips: present on GET /workitems/{childId}, present in lifecycle event

**Spawn API — error paths:**
- Invalid `templateId` → 422
- Non-existent parent → 404
- Terminal parent (COMPLETED, CANCELLED) → 409
- Duplicate `idempotencyKey` on same parent → 200 with existing group (idempotent)
- Empty `children` array → 422

**Cascade cancellation:**
- `DELETE /workitems/{id}/spawn-groups/{gid}?cancelChildren=false` → group CANCELLED, children unchanged
- `DELETE /workitems/{id}/spawn-groups/{gid}?cancelChildren=true` → group CANCELLED, all PENDING children CANCELLED, IN_PROGRESS children unchanged

**Template-driven spawn:**
- WorkItem from template with `spawnConfig`, reaches `triggerOn` status → children created automatically
- `quarkus.work.spawn.template-auto-spawn=false` → no auto-spawn fires
- `triggerOn=ASSIGNED`: fires on claim (ASSIGNED), not on CREATED

**CompletionRollup integration:**
- All children COMPLETED → `SpawnGroupCompletedEvent` CDI event captured, group status = COMPLETE
- M_OF_N=2 of 3: complete 2 → event fires, 3rd child still PENDING
- One child REJECTED → `ALL_MUST_COMPLETE` group stays ACTIVE (blocked)
- Manual cancel of one child (not via group cancel) → does not trigger rollup

**WorkEventType.SPAWNED:**
- After `POST /spawn`, audit trail on parent contains SPAWNED event
- Ledger entry for SPAWNED created on parent
- Child CREATED ledger entries carry `causedByEntryId` = parent SPAWNED entry ID

### End-to-end tests (scenario-style, @QuarkusTest)

**Scenario 1 — Standalone all-parallel:**
1. Create `loan-application` template with `spawnConfig` (3 children, `ALL_MUST_COMPLETE`)
2. Instantiate template → WorkItem created (PENDING)
3. Assign WorkItem → ASSIGNED, SpawnEngine fires → 3 child WorkItems created, PART_OF links verified
4. Complete all 3 children
5. Assert `SpawnGroupCompletedEvent` fired with all 3 outcomes
6. Assert parent auto-completes (config: `auto-complete-parent=true`)
7. Assert audit trail: parent has SPAWNED + COMPLETED entries; children have CREATED + COMPLETED

**Scenario 2 — M-of-N threshold:**
1. Spawn 3 children with `M_OF_N` threshold=2
2. Complete child 1 → no event
3. Complete child 2 → `SpawnGroupCompletedEvent` fires
4. Assert child 3 still PENDING (not auto-cancelled)
5. Complete child 3 → no second group event

**Scenario 3 — CaseHub pattern (caller-driven, no completionPolicy):**
1. Create parent WorkItem
2. `POST /workitems/{id}/spawn` with 3 children, `callerRef` set per child, `completionPolicy=null`
3. Verify 3 children PENDING, `callerRef` stored on each
4. Complete each child; verify `WorkItemLifecycleEvent` fired with correct `callerRef`
5. No `SpawnGroupCompletedEvent` (policy was null)

**Scenario 4 — Cascade cancellation:**
1. Spawn 3 children
2. Assign child 1 (IN_PROGRESS), leave 2 and 3 PENDING
3. `DELETE /spawn-groups/{id}?cancelChildren=true`
4. Assert group CANCELLED, children 2+3 CANCELLED, child 1 still IN_PROGRESS

**Scenario 5 — Idempotency under retry:**
1. `POST /workitems/{id}/spawn` with `idempotencyKey=key-1` → 201, 3 children
2. Same POST with `idempotencyKey=key-1` → 200, same group ID returned, no new children created
3. Verify child count is still 3

**Scenario 6 — REJECTED child blocks ALL_MUST_COMPLETE:**
1. Spawn 3 children, `ALL_MUST_COMPLETE`
2. Complete children 1 and 2
3. REJECT child 3
4. Assert `SpawnGroupCompletedEvent` NOT fired
5. Assert group status still ACTIVE

### Robustness tests

- **Parent cancelled mid-spawn**: children already created remain; group transitions to CANCELLED
- **Template deleted after spawn**: existing child WorkItems unaffected (no FK to template at runtime)
- **SpawnEngine CDI observer throws**: error logged, parent WorkItem state unchanged
- **`SpawnGroupCompletedEvent` observer throws**: error logged, group status update persisted regardless
- **Database unavailable during child creation**: partial spawn → transaction rolled back, no orphaned PART_OF links
- **`causedByEntryId` set to null when ledger module absent**: graceful degradation, spawn still succeeds

### Correctness tests

**PART_OF graph integrity:**
- Children are linked child→parent (source=child, target=parent), consistent with existing convention
- Cyclic PART_OF guard still enforced for manually-created PART_OF links
- Nested spawn: child from group A is itself a parent in group B — group resolution is independent per parent

**`callerRef` fidelity:**
- Unicode, special chars, long strings (255 chars) round-trip unchanged
- Null `callerRef` on one child → that child's events carry null; others unaffected

**Concurrency:**
- Two children completing at identical millisecond: exactly one `SpawnGroupCompletedEvent` fires
- Optimistic lock conflict on `SpawnGroup` retried once; second attempt succeeds

**Completion policy edge cases:**
- `ALL_MUST_COMPLETE` with 0 children → group immediately COMPLETE (vacuous truth)
- `M_OF_N` with threshold > child count → group never auto-completes (stuck ACTIVE until cancelled)
- `M_OF_N` threshold = 0 → 422 at POST time
- `completionPolicy=null` with 0 children → no group created, no event fired

---

## WorkItem model changes

New nullable field on `WorkItem`:

```java
@Column(name = "caller_ref", length = 512, nullable = true)
public String callerRef;
```

Added to:
- `WorkItemCreateRequest` (optional, passed through at spawn time)
- `WorkItemResponse` / any DTO that exposes WorkItem fields
- `WorkItemLifecycleEvent` source object (via `WorkItem.callerRef`)
- Flyway migration (next available version)

---

## What is explicitly NOT in scope

- Activation conditions on individual children (CaseHub's blackboard)
- Milestones (CaseHub's CMMN model)
- Ad-hoc fragment activation (CaseHub's case lifecycle)
- Sequential chaining (API accommodates it via future `spawnStrategy` field; implementation deferred)
- Cross-service spawn (distributed WorkItems epic #92)
- Dynamic spawn rule topology (CaseHub's domain — CaseHub decides what to spawn, when)
- Quarkus-Flow as spawn execution engine (deferred — would require `quarkus-work-spawn-flow` module)

---

## Open decisions (require CaseHub input)

1. **Orchestration vs choreography mode for WorkItems.** Does CaseHub use `WorkOrchestrator.submitAndWait()` (which registers a `CompletableFuture` in `PendingWorkRegistry` keyed on a `correlationKey`) for human WorkItems, or does it use the choreography path (observe CDI events, write to `CaseContext`, let the engine re-evaluate)? The `callerRef` mechanism supports both; CaseHub's adapter design depends on this choice.

2. **Resolution payload delivery.** Should the per-child `WorkItemLifecycleEvent` carry `WorkItem.resolution` inline, or should CaseHub's adapter call `GET /workitems/{id}` to retrieve it? Inline is simpler but makes the event larger. Fetch is a second network call but keeps events lean.

3. **`WorkItemSpawnGroup` public API.** The group entity and its REST endpoints (`GET /spawn-groups/{id}`, etc.) are documented as "standalone only." Should these endpoints be omitted entirely from a CaseHub deployment, or simply documented as non-canonical? A profile/config flag could suppress them.
