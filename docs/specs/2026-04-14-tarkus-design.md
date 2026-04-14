# Quarkus Tarkus — Design Specification v1.0

> *Human-scale WorkItem lifecycle management for the Quarkus Native AI Agent Ecosystem.*

---

## Glossary

Three systems in this ecosystem all use the word "task" to mean different things. This glossary is authoritative.

**Task** *(io.serverlessworkflow.api.types.Task — CNCF Serverless Workflow / Quarkus-Flow)*
A step within a workflow definition executed under machine control. Has lifecycle:
started → completed / failed / suspended / resumed. Executes in milliseconds to seconds.
No assignee, no delegation, no expiry. The engine decides when and how it runs.
Examples: call an HTTP endpoint, emit a CloudEvent, evaluate a JQ expression.

**Task** *(io.casehub.worker.Task — CaseHub)*
A unit of work within a CaseHub case, following CMMN terminology. Assigned to a worker
(human or agent) via capability matching. Has lifecycle: PENDING → ASSIGNED → RUNNING →
WAITING → COMPLETED / FAULTED / CANCELLED. The task model is unified — human workers
and agent workers are the same concept in CaseHub. When a CaseHub Task is routed to a
human worker, the `quarkus-tarkus-casehub` adapter creates a corresponding Tarkus WorkItem.

**WorkItem** *(io.quarkiverse.tarkus.runtime.model.WorkItem — Quarkus Tarkus)*
A unit of work requiring human attention or judgment. Has lifecycle:
PENDING → ASSIGNED → IN_PROGRESS → COMPLETED / REJECTED / SUSPENDED / CANCELLED / EXPIRED → ESCALATED.
Persists minutes to days. Has assignee, candidate groups, priority, deadlines, delegation chain,
follow-up date, category, form reference, and full audit trail. Any system creates one —
Quarkus-Flow, CaseHub, Qhorus, or a plain REST call. A human resolves it.

**The one-sentence rule:** A `Task` is controlled by a machine. A `WorkItem` waits for a human.

---

## What Tarkus Is

Quarkus Tarkus is a standalone Quarkiverse extension providing a **human task inbox** — a place for human workers to see what needs their attention, act on it, delegate it, and have it automatically escalate when it expires.

It is **not** a workflow engine, a case manager, or an agent communication mesh. It is the layer that sits between those systems and the human who needs to make decisions.

Any Quarkus application can embed Tarkus to get:
- A `WorkItem` entity with full lifecycle management
- A REST inbox API that any UI (Claudony dashboard, custom frontend) can consume
- Expiry detection and pluggable escalation policies
- Delegation chains with full audit trail
- CloudEvent emission for integration with external systems

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Quarkus Tarkus (standalone)                                    │
│                                                                  │
│  REST API /tarkus/workitems  ←── Claudony dashboard             │
│         │                        (or any UI)                    │
│         ▼                                                        │
│  WorkItemService                                                 │
│  ├── create / assign / claim / complete / reject / delegate     │
│  ├── ExpiryCleanupJob (@Scheduled)                               │
│  └── EscalationPolicy SPI                                       │
│         │                                                        │
│         ▼                                                        │
│  WorkItemRepository SPI  ←── JpaWorkItemRepository (default)   │
│  AuditEntryRepository SPI ←── JpaAuditEntryRepository (default) │
│         │                     (Panache, H2/PostgreSQL)          │
│         │                                                        │
│         └── InMemoryWorkItemRepository (quarkus-tarkus-testing) │
└─────────────────────────────────────────────────────────────────┘

Optional integration modules (separate artifacts, future):
  quarkus-tarkus-flow      →  TaskExecutorFactory SPI (Quarkus-Flow)
  quarkus-tarkus-casehub   →  WorkerRegistry adapter (CaseHub)
  quarkus-tarkus-qhorus    →  MCP tools (Qhorus)
  quarkus-tarkus-testing   →  InMemoryWorkItemRepository for unit tests
  quarkus-tarkus-mongodb   →  MongoDB-backed repository (future)
  quarkus-tarkus-redis     →  Redis-backed repository (future)
```

---

## WorkItem Model

```java
// io.quarkiverse.tarkus.runtime.model.WorkItem
@Entity @Table(name = "work_item")
public class WorkItem extends PanacheEntityBase {
    @Id public UUID id;

    // Core description
    public String title;
    public String description;
    public String category;               // free-text classification: "finance", "legal", "security-review"
    public String formKey;                // UI form reference — how a frontend should render this item

    // Status
    @Enumerated(EnumType.STRING)
    public WorkItemStatus status;

    @Enumerated(EnumType.STRING)
    public WorkItemPriority priority;     // LOW|NORMAL|HIGH|CRITICAL

    // Assignment — work queue model
    public String assigneeId;            // who currently has it (actual owner)
    public String owner;                 // who is ultimately responsible (set on first delegation)
    public String candidateGroups;       // comma-separated groups who can claim (null = pre-assigned)
    public String candidateUsers;        // comma-separated individual users who can claim
    public String requiredCapabilities;  // comma-separated capability tags for routing
    public String createdBy;             // system or agent that created it

    // Delegation
    @Enumerated(EnumType.STRING)
    public DelegationState delegationState;  // null | PENDING | RESOLVED
    public String delegationChain;           // comma-separated prior assignees (audit trail)

    // Payload
    @Column(columnDefinition = "TEXT")
    public String payload;               // JSON context for the human to act on
    @Column(columnDefinition = "TEXT")
    public String resolution;            // JSON decision written by the human

    // Deadlines
    public Instant claimDeadline;        // must be claimed by — breach triggers claim escalation
    public Instant expiresAt;            // must be completed by — breach triggers completion escalation
    public Instant followUpDate;         // reminder date; surfaces in inbox, no escalation

    // Timestamps (aligned with quarkus-flow lifecycle event naming)
    public Instant createdAt;
    public Instant updatedAt;
    public Instant assignedAt;           // when claimed/assigned
    public Instant startedAt;            // when IN_PROGRESS began
    public Instant completedAt;          // when COMPLETED, REJECTED, CANCELLED, or EXPIRED
    public Instant suspendedAt;          // when SUSPENDED
}

// io.quarkiverse.tarkus.runtime.model.DelegationState
public enum DelegationState {
    PENDING,   // delegated to another; they are working it
    RESOLVED   // delegate completed; owner must confirm
}

// io.quarkiverse.tarkus.runtime.model.AuditEntry
@Entity @Table(name = "audit_entry")
public class AuditEntry extends PanacheEntityBase {
    @Id public UUID id;
    public UUID workItemId;
    public String event;                 // see AuditEvent enum values below
    public String actor;                 // who performed the action
    public String detail;                // JSON detail (e.g., previous assignee on delegation)
    public Instant occurredAt;
}

// Audit event values (aligned with quarkus-flow task event naming)
// CREATED | ASSIGNED | STARTED | COMPLETED | REJECTED | DELEGATED
// RELEASED | SUSPENDED | RESUMED | CANCELLED | EXPIRED | ESCALATED
```

**WorkItemStatus — aligned with quarkus-flow (`SUSPENDED`, `CANCELLED` are identical names):**

```
PENDING      — available for claiming; no assignee yet (or returned to pool after DELEGATED/RELEASED)
ASSIGNED     — claimed by a specific person; not yet actively working
IN_PROGRESS  — being actively worked (≈ RUNNING in quarkus-flow)
COMPLETED    — successfully resolved (identical to quarkus-flow)
REJECTED     — human declined or declared uncomplete-able (≈ FAULTED in quarkus-flow)
DELEGATED    — transitional: ownership transferred, pending new assignment
SUSPENDED    — on hold; will resume (identical to quarkus-flow SUSPENDED)
CANCELLED    — externally cancelled by system or admin (identical to quarkus-flow CANCELLED)
EXPIRED      — passed completion deadline without resolution; triggers escalation policy
ESCALATED    — escalation policy has fired post-expiry (terminal or awaiting admin action)
```

**Lifecycle transitions:**
```
PENDING → ASSIGNED (claim)
        → CANCELLED (admin)

ASSIGNED → IN_PROGRESS (start)
         → DELEGATED → PENDING (for new assignee)
         → RELEASED → PENDING (relinquish back to pool)
         → SUSPENDED
         → CANCELLED (admin)

IN_PROGRESS → COMPLETED
            → REJECTED
            → DELEGATED → PENDING
            → SUSPENDED
            → CANCELLED (admin)

SUSPENDED → ASSIGNED | IN_PROGRESS (resume to prior state)
          → CANCELLED (admin)

PENDING | ASSIGNED | IN_PROGRESS | SUSPENDED → EXPIRED (ExpiryCleanupJob)
EXPIRED → ESCALATED (EscalationPolicy SPI)
```

**DelegationState transitions:**
```
(null) → PENDING  (delegate operation)
PENDING → RESOLVED  (delegate completes; owner must confirm)
RESOLVED → (null)   (owner confirms; item re-enters normal lifecycle)
```

---

## REST API

Base path: `/tarkus/workitems`

**Inbox and query:**

| Method | Path | Description |
|---|---|---|
| `GET` | `/inbox` | Human inbox: `?assignee=X&candidateGroup=Y&candidateUser=Z&status=PENDING&priority=HIGH&category=finance&followUp=true` |
| `GET` | `/` | List all WorkItems (admin) |
| `GET` | `/{id}` | Get full WorkItem with audit log |
| `POST` | `/` | Create a WorkItem |

**Lifecycle operations:**

| Method | Path | Transition | Notes |
|---|---|---|---|
| `PUT` | `/{id}/claim` | PENDING → ASSIGNED | Caller becomes assignee |
| `PUT` | `/{id}/start` | ASSIGNED → IN_PROGRESS | Begin work |
| `PUT` | `/{id}/complete` | IN_PROGRESS → COMPLETED | Body: resolution JSON |
| `PUT` | `/{id}/reject` | ASSIGNED\|IN_PROGRESS → REJECTED | Body: reason |
| `PUT` | `/{id}/delegate` | → DELEGATED → PENDING | `?to=assigneeId`; sets owner if first delegation |
| `PUT` | `/{id}/release` | ASSIGNED → PENDING | Relinquish back to candidate pool |
| `PUT` | `/{id}/suspend` | ASSIGNED\|IN_PROGRESS → SUSPENDED | Body: reason |
| `PUT` | `/{id}/resume` | SUSPENDED → prior state | Restores ASSIGNED or IN_PROGRESS |
| `PUT` | `/{id}/cancel` | any → CANCELLED | Admin only; body: reason |

**Inbox query parameters:**

| Parameter | Meaning |
|---|---|
| `assignee` | Items directly assigned to this user |
| `candidateGroup` | Items claimable by this group |
| `candidateUser` | Items this user was individually invited to claim |
| `status` | Filter by status (default: PENDING,ASSIGNED,IN_PROGRESS) |
| `priority` | Filter by priority |
| `category` | Filter by category |
| `followUp=true` | Items where followUpDate ≤ now (reminder view) |

The inbox query uses OR across `assignee`, `candidateGroup`, `candidateUser` — returning all items the caller can see or act on.

**Create request body:**
```json
{
  "title": "Review auth-refactor analysis",
  "description": "Alice's security analysis needs sign-off before proceeding",
  "assigneeId": "alice",
  "candidateGroups": "security-team,leads",
  "priority": "HIGH",
  "category": "security-review",
  "formKey": "tarkus/security-approval/v1",
  "claimDeadline": "2026-04-15T09:00:00Z",
  "expiresAt": "2026-04-15T12:00:00Z",
  "followUpDate": "2026-04-15T08:00:00Z",
  "payload": { "analysisRef": "uuid-of-shared-data-artefact", "channelName": "auth-refactor" }
}
```

---

## Configuration

`TarkusConfig` — `@ConfigMapping(prefix = "quarkus.tarkus")`:

| Property | Default | Meaning |
|---|---|---|
| `quarkus.tarkus.default-expiry-hours` | 24 | Default completion deadline if no `expiresAt` is set |
| `quarkus.tarkus.default-claim-hours` | 4 | Default claim deadline if no `claimDeadline` is set (0 = no claim deadline) |
| `quarkus.tarkus.escalation-policy` | notify | What happens on completion expiry: `notify`, `reassign`, `auto-reject` |
| `quarkus.tarkus.claim-escalation-policy` | notify | What happens on claim deadline breach: `notify`, `reassign` |
| `quarkus.tarkus.cleanup.expiry-check-seconds` | 60 | How often the expiry/claim-deadline job runs |

Consuming app owns datasource config — none in the extension's `application.properties`.

---

## Storage SPI

Persistence is pluggable via two CDI interfaces in `io.quarkiverse.tarkus.runtime.repository`:

```java
public interface WorkItemRepository {
    WorkItem save(WorkItem workItem);
    Optional<WorkItem> findById(UUID id);
    List<WorkItem> findAll();
    /**
     * Inbox query — returns items where any of the following match (OR):
     *   - assigneeId equals the given assignee
     *   - candidateGroups contains any of the given groups
     *   - candidateUsers contains the given assignee
     * Additional filters (all nullable = "any"): status, priority, category, followUpBefore.
     */
    List<WorkItem> findInbox(String assignee, List<String> candidateGroups,
                             WorkItemStatus status, WorkItemPriority priority,
                             String category, Instant followUpBefore);
    /** Items where expiresAt <= now AND status is PENDING, ASSIGNED, IN_PROGRESS, or SUSPENDED */
    List<WorkItem> findExpired(Instant now);
    /** Items where claimDeadline <= now AND status is PENDING */
    List<WorkItem> findUnclaimedPastDeadline(Instant now);
}

public interface AuditEntryRepository {
    void append(AuditEntry entry);
    List<AuditEntry> findByWorkItemId(UUID workItemId);
}
```

**Default implementations** (`runtime.repository.jpa`):
- `JpaWorkItemRepository` — Panache-backed; registered `@ApplicationScoped`
- `JpaAuditEntryRepository` — Panache-backed; registered `@ApplicationScoped`

**Test implementation** (`quarkus-tarkus-testing` module):
- `InMemoryWorkItemRepository` — `ConcurrentHashMap`-backed; no datasource required
- `InMemoryAuditEntryRepository` — list-backed
- Register as `@ApplicationScoped @Alternative @Priority(1)` to override the JPA defaults

**Custom implementations** — any consuming app or module can provide an alternative:
```java
@ApplicationScoped
@Alternative
@Priority(1)
public class MyRedisWorkItemRepository implements WorkItemRepository { ... }
```

The JPA default requires a datasource. When using `quarkus-tarkus-testing`, no datasource
or Flyway migration is needed — making pure unit tests (no `@QuarkusTest`) trivial.

---

## Escalation Policy SPI

```java
// io.quarkiverse.tarkus.runtime.service.EscalationPolicy
public interface EscalationPolicy {
    /** Called when a WorkItem's expiresAt passes without resolution. */
    void escalate(WorkItem workItem);
}
```

Default implementations (selectable via `quarkus.tarkus.escalation-policy`):
- `notify` — emits a `workitem.expired` CloudEvent; human must act
- `reassign` — moves to next assignee in a capability pool
- `auto-reject` — auto-rejects and records in audit log

Custom implementations register as CDI beans with `@Singleton @Alternative @Priority(1)`.

---

## CloudEvent Emission

Tarkus emits CloudEvents for all lifecycle transitions (via Quarkus Messaging):

| Event type | When |
|---|---|
| `io.quarkiverse.tarkus.workitem.created` | WorkItem created |
| `io.quarkiverse.tarkus.workitem.assigned` | WorkItem claimed or assigned |
| `io.quarkiverse.tarkus.workitem.completed` | WorkItem completed |
| `io.quarkiverse.tarkus.workitem.rejected` | WorkItem rejected |
| `io.quarkiverse.tarkus.workitem.delegated` | WorkItem delegated |
| `io.quarkiverse.tarkus.workitem.expired` | WorkItem expired |
| `io.quarkiverse.tarkus.workitem.escalated` | Escalation policy fired |

---

## Integration Modules (Future)

### quarkus-tarkus-flow
Implements `io.serverlessworkflow.impl.executors.TaskExecutorFactory` (Java SPI via
`META-INF/services`). When a Quarkus-Flow workflow step matches the Tarkus handler
(e.g., a custom `humanTask` type), the factory:
1. Creates a Tarkus WorkItem from the step definition
2. Suspends the WorkflowInstance (returns an incomplete CompletableFuture)
3. Completes the CompletableFuture with the WorkItem resolution when the human acts
4. Quarkus-Flow resumes the workflow with the resolution as output

### quarkus-tarkus-casehub
Registers a worker with CaseHub's `WorkerRegistry` claiming `human:*` capability tasks.
When a CaseHub Task is claimed:
1. Creates a Tarkus WorkItem with the CaseHub task context as payload
2. On WorkItem completion, calls `WorkerRegistry.submitResult()` to advance the case

### quarkus-tarkus-qhorus
Adds MCP tools backed by the Tarkus REST API:
- `request_approval(title, description, assignee, payload, timeout_s)` → creates WorkItem, returns `workItemId`
- `check_approval(work_item_id)` → returns status and resolution
- `wait_for_approval(work_item_id, timeout_s)` → polls until resolved or timeout

---

## Build Roadmap

| Phase | Status | What |
|---|---|---|
| **1 — Core data model** | ⬜ Pending | Storage SPI interfaces, JPA defaults, InMemory impl (testing module), WorkItem + AuditEntry entities, Flyway V1, WorkItemService, TarkusConfig |
| **2 — REST API** | ⬜ Pending | WorkItemResource — all CRUD + inbox + lifecycle endpoints |
| **3 — Lifecycle engine** | ⬜ Pending | ExpiryCleanupJob, EscalationPolicy SPI, default implementations |
| **4 — CloudEvents** | ⬜ Pending | Event emission on all lifecycle transitions |
| **5 — Quarkus-Flow integration** | ⬜ Pending | `quarkus-tarkus-flow` module, TaskExecutorFactory SPI |
| **6 — CaseHub integration** | ⬜ Pending | `quarkus-tarkus-casehub` module, WorkerRegistry adapter |
| **7 — Qhorus integration** | ⬜ Pending | `quarkus-tarkus-qhorus` module, MCP tools |
| **8 — Native image validation** | ⬜ Pending | GraalVM native build, reflection config, native tests |

---

## Future Considerations

Deliberately deferred — not in scope for v1 but worth revisiting as the ecosystem matures.
Sources: WS-HumanTask (OASIS), BPMN 2.0, CMMN, Camunda 8, Flowable, Activiti.

### Multi-tenancy (`tenantId`)

Add `tenantId: String` to `WorkItem` and `AuditEntry`. Required when a single Quarkus
application serves multiple tenants. All queries would include a tenant filter.
Quarkus multi-tenancy infrastructure would need to be wired in. Deferred because the
initial integration targets (Qhorus, CaseHub, Quarkus-Flow) are single-tenant.

### Subtasks (`parentWorkItemId`)

A `WorkItem` could be a child of another `WorkItem`. Parent completes when all children
complete. Modelled as a `parentWorkItemId: UUID` FK and a completion rollup in
`WorkItemService`. Deferred because it adds significant lifecycle complexity and no
integration target currently requires it. CaseHub integration may surface this need.

### Multi-approver patterns

Approval flows where multiple humans must act — all must approve (quorum), any one suffices,
or a sequential chain. Current model supports this by creating multiple WorkItems and
coordinating at the calling layer (e.g., Quarkus-Flow workflow). Tarkus could natively
model it with: `approvalType: enum (ANY_OF, ALL_OF, SEQUENTIAL)`, `requiredApprovals: int`,
and a parent/child relationship. Deferred — the calling-layer composition approach is
sufficient for v1.

### Discretionary (ad-hoc) tasks

CMMN concept: a task that is *optional* — the human decides whether to perform it at all.
Modelled as `discretionary: boolean`. A discretionary WorkItem appears in the "task planner"
view; the human explicitly adds it to their work if they choose to act on it. Potentially
useful for the Qhorus integration (AI agent suggests optional investigative steps). Deferred
pending real use cases.

### `skipable` flag + skip operation

WS-HumanTask allows tasks to be marked as skipable by a business administrator without the
task being performed. Requires `skipable: boolean` on `WorkItem` and a `PUT /{id}/skip`
endpoint with admin authorisation. Useful for workflow un-blocking scenarios. Deferred.

### Information-request state (`WAITING_FOR_INFO`)

Enterprise BPM pattern: an assignee asks the task creator a clarifying question, putting the
task on hold until the creator responds. Modelled as an additional status `WAITING_FOR_INFO`
with a sub-thread of messages. Complex to implement well. Deferred — `SUSPENDED` with a
detail note in the audit log approximates this for v1.

### Routing strategies

Work-queue routing beyond candidateGroups: round-robin within a group, load-balanced by
current IN_PROGRESS count, organizational-hierarchy-based. Modelled as a routing strategy
SPI alongside the escalation policy SPI. Deferred — candidateGroups + manual claim covers
most cases.

### `taskDefinitionKey` / workflow linkage

When a WorkItem is created by a workflow engine (Quarkus-Flow, CaseHub), storing the source
workflow definition ID and step ID enables dashboards to show "which workflow generated this
task" and supports analytics across workflow runs. Fields: `sourceSystem: String`,
`sourceDefinitionId: String`, `sourceInstanceId: String`. Deferred to the integration
module phases (Phases 5–7) where the integration adapters can populate these fields.

---

*This specification emerged from design discussions during the quarkus-qhorus project (2026-04-14), which identified the need for a standalone human task layer across CaseHub, Quarkus-Flow, and Qhorus. The WorkItem model was informed by WS-HumanTask (OASIS), BPMN 2.0 UserTask, CMMN HumanTask, Camunda 8, Flowable, and Activiti.*
