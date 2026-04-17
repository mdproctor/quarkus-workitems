# Quarkus WorkItems — Integration Guide

This guide covers seven integration patterns: standalone REST, Quarkus-Flow workflow suspension, CDI lifecycle event observation, custom escalation policies, unit testing without a datasource, the optional ledger module, and the `WorkItemsFlow` DSL for embedding WorkItem steps directly in workflow definitions.

---

## Section 1: Standalone REST Integration

Any system that can make HTTP requests integrates with WorkItems through its REST API. This covers curl scripts, frontend applications, other microservices, and non-Quarkus backends.

### Typical flow

1. **Create a WorkItem** — the calling system creates the item and records the returned `id`.
2. **Surface it to a human** — a frontend polls `GET /inbox` or the calling system notifies the assignee out-of-band.
3. **Human acts on it** — the frontend drives the claim/start/complete sequence.
4. **Poll for completion** — the calling system polls `GET /{id}` and inspects `status`.

### Complete curl example

```bash
#!/usr/bin/env bash
BASE=http://localhost:8080/workitems

# Step 1: Create
RESPONSE=$(curl -s -X POST "$BASE" \
  -H 'Content-Type: application/json' \
  -d '{
    "title": "Approve expense report",
    "description": "Q1 travel expenses for Alice — $1,840. Approve or reject.",
    "category": "finance",
    "priority": "NORMAL",
    "candidateGroups": "finance-team",
    "createdBy": "expense-service",
    "payload": "{\"reportId\": \"EXP-2026-0042\", \"amount\": 1840}"
  }')

ID=$(echo "$RESPONSE" | jq -r '.id')
echo "Created WorkItem: $ID"

# Step 2: Finance team member claims it
curl -s -X PUT "$BASE/$ID/claim?claimant=bob" | jq .status

# Step 3: Start work
curl -s -X PUT "$BASE/$ID/start?actor=bob" | jq .status

# Step 4: Complete with a decision
curl -s -X PUT "$BASE/$ID/complete?actor=bob" \
  -H 'Content-Type: application/json' \
  -d '{"resolution": "{\"approved\": true, \"notes\": \"Within policy\"}"}'

# Step 5: Calling system polls for completion
STATUS=$(curl -s "$BASE/$ID" | jq -r '.status')
echo "Final status: $STATUS"
RESOLUTION=$(curl -s "$BASE/$ID" | jq -r '.resolution')
echo "Resolution: $RESOLUTION"
```

### Inbox polling

A frontend showing a user's pending work polls the inbox endpoint:

```bash
# Show all items for bob (direct assignment or group membership)
curl "http://localhost:8080/workitems/inbox?assignee=bob"

# Show only high-priority items for the finance-team group
curl "http://localhost:8080/workitems/inbox?candidateGroup=finance-team&priority=HIGH"
```

The response is a `WorkItemResponse[]` ordered by creation time. Frontends use `formKey` to decide which form component to render for each item, and `payload` to prepopulate the form with context.

---

## Section 2: Quarkus-Flow Integration

The `quarkus-workitems-flow` module suspends a Quarkus-Flow workflow until a human resolves a WorkItem, then resumes the workflow with the resolution JSON. See [Section 7](#section-7-quarkus-flow-dsl-workitemsflow) for the higher-level `WorkItemsFlow` DSL, which is the preferred approach for new workflows.

### Dependency

```xml
<dependency>
  <groupId>io.quarkiverse.workitems</groupId>
  <artifactId>quarkus-workitems-flow</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

This pulls in `quarkus-workitems` transitively.

### How it works

1. A flow task function calls `HumanTaskFlowBridge.requestApproval()` (or `requestGroupApproval()`).
2. The bridge creates a WorkItem via `WorkItemService` and registers a `CompletableFuture` in `PendingWorkItemRegistry`, keyed by the WorkItem's UUID.
3. The method returns a `Uni<String>` wrapping that `CompletableFuture`; Quarkus-Flow suspends the workflow on the `Uni`.
4. A human sees the WorkItem in `GET /inbox`, claims it, and completes it via `PUT /{id}/complete`.
5. `WorkItemService` fires a `WorkItemLifecycleEvent` with type `io.quarkiverse.workitems.workitem.completed`.
6. `WorkItemFlowEventListener` observes the event and calls `PendingWorkItemRegistry.complete()`, resolving the `CompletableFuture` with the resolution JSON.
7. The `Uni` completes and the flow resumes with the resolution as its output.

If the WorkItem is rejected or cancelled, the `Uni` fails with `WorkItemResolutionException`.

### Direct assignee example

```java
@ApplicationScoped
public class DocumentApprovalFlow extends Flow {

    @Inject
    HumanTaskFlowBridge humanTask;

    @Override
    public Workflow descriptor() {
        return workflow("document-approval")
            .tasks(
                function("request-review", (String documentId) ->
                    humanTask.requestApproval(
                        "Review document for publication",              // title
                        "Check for accuracy and approve or reject.",   // description
                        "alice",                                       // direct assignee
                        WorkItemPriority.HIGH,                         // priority
                        "{\"documentId\": \"" + documentId + "\"}"    // payload (JSON context)
                    ), String.class)
            )
            .build();
    }
}
```

The `Uni<String>` resolves with the resolution JSON that `alice` submits when completing the WorkItem:

```bash
curl -X PUT "http://localhost:8080/workitems/{id}/complete?actor=alice" \
  -H 'Content-Type: application/json' \
  -d '{"resolution": "{\"approved\": true, \"publishAt\": \"2026-04-15T09:00:00Z\"}"}'
```

The flow then receives `"{\"approved\": true, \"publishAt\": \"2026-04-15T09:00:00Z\"}"` as its output.

### Group routing example

Use `requestGroupApproval()` when you want any member of a group to be able to act, rather than a specific individual:

```java
humanTask.requestGroupApproval(
    "Legal review required",
    "Review contract before signing.",
    "legal-team,senior-managers",   // comma-separated candidate groups
    WorkItemPriority.CRITICAL,
    "{\"contractId\": \"" + contractId + "\"}"
)
```

The WorkItem appears in the inbox for all members of `legal-team` and `senior-managers`. The first to claim it becomes the assignee; others no longer see it in their active inbox (though `GET /inbox?candidateGroup=legal-team` will still return it until claimed).

### Handling rejection and cancellation

```java
humanTask.requestApproval(title, description, assignee, priority, payload)
    .onItem().transform(resolution -> {
        // WorkItem was COMPLETED — resolution is the JSON string
        return parseDecision(resolution);
    })
    .onFailure(WorkItemResolutionException.class).recoverWithItem(ex -> {
        // WorkItem was REJECTED or CANCELLED
        log.warnf("WorkItem %s not resolved: %s", ex.getWorkItemId(), ex.getMessage());
        return null;
    });
```

---

## Section 3: Observing Lifecycle Events

Any CDI bean in the same application can observe `WorkItemLifecycleEvent` without any dependency on the REST layer or Quarkus-Flow. This is the recommended way to react to WorkItem transitions (notifications, metrics, audit forwarding, webhook dispatch, etc.).

### Basic observer

```java
@ApplicationScoped
public class WorkItemAuditLogger {

    void onWorkItemEvent(@Observes WorkItemLifecycleEvent event) {
        Log.infof("[%s] WorkItem %s → status=%s actor=%s detail=%s",
            event.type(),
            event.workItemId(),
            event.status(),
            event.actor(),
            event.detail());
    }
}
```

### Filtering by event type

```java
@ApplicationScoped
public class CompletionNotifier {

    @Inject
    NotificationService notifications;

    void onCompleted(@Observes WorkItemLifecycleEvent event) {
        if (!"io.quarkiverse.workitems.workitem.completed".equals(event.type())) {
            return;
        }
        notifications.send(event.actor(),
            "WorkItem " + event.workItemId() + " completed. Resolution: " + event.detail());
    }

    void onExpired(@Observes WorkItemLifecycleEvent event) {
        if (!"io.quarkiverse.workitems.workitem.expired".equals(event.type())) {
            return;
        }
        notifications.alertAdmin("WorkItem " + event.workItemId() + " has expired!");
    }
}
```

### Event type reference

All event types follow the pattern `io.quarkiverse.workitems.workitem.{action}` where `{action}` is the lowercase audit event name. See the [API Reference — Lifecycle event types](api-reference.md#lifecycle-event-types) for the full table.

### WorkItemLifecycleEvent fields

| Field | Type | Description |
|---|---|---|
| `type` | string | Full event type string (e.g. `io.quarkiverse.workitems.workitem.completed`) |
| `source` | string | `/workitems/{workItemId}` |
| `subject` | string | WorkItem UUID as string |
| `workItemId` | UUID | WorkItem UUID |
| `status` | WorkItemStatus | Status AFTER the transition |
| `occurredAt` | Instant | When the event was fired |
| `actor` | string | Who triggered the transition |
| `detail` | string | Optional detail — resolution JSON, rejection reason, delegation target, etc. |

CDI events are synchronous and fired within the same transaction as the status transition. If your observer needs to do something outside the transaction (e.g. send an HTTP notification), use `@Observes(during = TransactionPhase.AFTER_SUCCESS)` to ensure the transition committed first.

---

## Section 4: Custom Escalation Policy

The `EscalationPolicy` SPI has two methods: `onExpired()` called when a WorkItem's `expiresAt` passes, and `onUnclaimedPastDeadline()` called when `claimDeadline` passes without the item being claimed. The built-in policies (`notify`, `reassign`, `auto-reject`) cover common cases. Override with a custom bean for anything else.

### Implementing EscalationPolicy

```java
@ApplicationScoped
@Alternative
@Priority(1)
public class SlackEscalationPolicy implements EscalationPolicy {

    @Inject
    SlackClient slack;

    @Override
    public void onExpired(WorkItem workItem) {
        slack.sendMessage(
            "#task-alerts",
            String.format("WorkItem expired: *%s* (id=%s, assignee=%s, priority=%s)",
                workItem.title, workItem.id, workItem.assigneeId, workItem.priority)
        );
        // Optionally also mutate the WorkItem status here, or delegate to another policy
    }

    @Override
    public void onUnclaimedPastDeadline(WorkItem workItem) {
        slack.sendMessage(
            "#task-alerts",
            String.format("WorkItem unclaimed past deadline: *%s* (id=%s, candidateGroups=%s)",
                workItem.title, workItem.id, workItem.candidateGroups)
        );
    }
}
```

`@Alternative @Priority(1)` causes CDI to select your bean over WorkItems's default implementation. No `application.properties` change is needed for the bean selection itself, but the `quarkus.workitems.escalation-policy` property is still read by the built-in policies — set it to `notify` (or any value) so the config validation passes if the built-in beans are still on the classpath.

```properties
quarkus.workitems.escalation-policy=notify
quarkus.workitems.claim-escalation-policy=notify
```

The expiry cleanup job (`ExpiryCleanupJob`) runs on the schedule configured by `quarkus.workitems.cleanup.expiry-check-seconds` (default 60s). It calls your `EscalationPolicy` bean once per breached WorkItem per scan.

---

## Section 5: Unit Testing with quarkus-workitems-testing

The `quarkus-workitems-testing` module provides in-memory implementations of `WorkItemStore` and `AuditEntryStore`. These override the default JPA implementations via `@Alternative @Priority(1)`, so no datasource or Flyway configuration is needed in tests.

### Dependency

```xml
<dependency>
  <groupId>io.quarkiverse.workitems</groupId>
  <artifactId>quarkus-workitems-testing</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

### QuarkusTest example

```java
@QuarkusTest
class ExpenseApprovalFlowTest {

    @Inject
    WorkItemService service;

    @Inject
    InMemoryWorkItemStore repo;

    @Inject
    InMemoryAuditEntryStore auditRepo;

    @BeforeEach
    void setUp() {
        repo.clear();
        auditRepo.clear();
    }

    @Test
    void createWorkItem_setsDefaultPriority() {
        WorkItemCreateRequest request = new WorkItemCreateRequest(
            "Approve expense", null, "finance", null,
            null,           // priority — should default to NORMAL
            "alice", null, null, null,
            "test", null, null, null, null
        );

        WorkItem created = service.create(request);

        assertThat(created.priority).isEqualTo(WorkItemPriority.NORMAL);
        assertThat(created.status).isEqualTo(WorkItemStatus.PENDING);
    }

    @Test
    void claim_transitionsToAssigned() {
        WorkItem item = service.create(new WorkItemCreateRequest(
            "Review contract", null, null, null, WorkItemPriority.HIGH,
            null, null, null, null, "test", null, null, null, null
        ));

        WorkItem claimed = service.claim(item.id, "bob");

        assertThat(claimed.status).isEqualTo(WorkItemStatus.ASSIGNED);
        assertThat(claimed.assigneeId).isEqualTo("bob");
        assertThat(claimed.assignedAt).isNotNull();
    }

    @Test
    void claim_rejectsNonPendingItem() {
        WorkItem item = service.create(new WorkItemCreateRequest(
            "Review contract", null, null, null, null,
            null, null, null, null, "test", null, null, null, null
        ));
        service.claim(item.id, "bob");

        assertThatThrownBy(() -> service.claim(item.id, "carol"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot claim WorkItem in status: ASSIGNED");
    }
}
```

### Plain unit test (no @QuarkusTest)

The in-memory repositories can also be used outside of Quarkus CDI for pure unit tests:

```java
class WorkItemServiceUnitTest {

    InMemoryWorkItemStore workItemStore = new InMemoryWorkItemStore();
    InMemoryAuditEntryStore auditStore = new InMemoryAuditEntryStore();
    WorkItemsConfig config = /* mock or test double */;

    WorkItemService service = new WorkItemService(workItemStore, auditStore, config);

    @BeforeEach
    void setUp() {
        workItemStore.clear();
        auditStore.clear();
    }

    // tests as above, no Quarkus boot time
}
```

This approach gives instant test execution — no Quarkus boot, no H2, no Flyway. Use `@QuarkusTest` only when you need CDI wiring or the REST layer.

### InMemoryWorkItemStore behaviour notes

- Not thread-safe — designed for single-threaded test use only.
- `put()` assigns a random UUID if `workItem.id` is null.
- `scan(WorkItemQuery.inbox(...))` matches using OR across `assigneeId`, `candidateGroups`, and `candidateUserId`, then applies AND for status, priority, category, and followUp filters — identical semantics to the JPA implementation.
- `scan(WorkItemQuery.expired(now))` returns items in `PENDING`, `ASSIGNED`, `IN_PROGRESS`, or `SUSPENDED` whose `expiresAt` is in the past.
- `scan(WorkItemQuery.claimExpired(now))` returns `PENDING` items whose `claimDeadline` is in the past.
- Candidate group matching uses exact comma-separated token comparison — `"bob"` does not match `"bobby"`.

---

## Section 6: Using the Ledger Module (quarkus-workitems-ledger)

The ledger module adds an optional accountability layer to  WorkItems.a per-WorkItem command/event ledger with a SHA-256 hash chain, decision context snapshots, peer attestations, and EigenTrust reputation scoring. The core extension is completely unchanged when the module is absent.

### Dependency

```xml
<dependency>
  <groupId>io.quarkiverse.workitems</groupId>
  <artifactId>quarkus-workitems-ledger</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### What activates automatically

No code changes are needed. The ledger module registers a CDI observer on `WorkItemLifecycleEvent`. When the module is on the classpath:

- Every WorkItem lifecycle transition writes a `LedgerEntry` with `commandType`, `eventType`, a JSON snapshot of the WorkItem state at that moment (`decisionContext`), and a SHA-256 digest chained to the previous entry's digest.
- The ledger REST endpoints become available automatically.

### Ledger endpoints

```bash
# Retrieve all ledger entries for a WorkItem (each with its attestations)
GET /workitems/{id}/ledger

# Record the source entity that created this WorkItem (called by CaseHub, Quarkus-Flow, etc.)
PUT /workitems/{id}/ledger/provenance

# Post a peer attestation on a specific ledger entry
POST /workitems/{id}/ledger/{entryId}/attestations

# Get the computed trust score for an actor (requires trust-score.enabled=true)
GET /workitems/actors/{actorId}/trust
```

See the [Ledger API section of the API Reference](api-reference.md#ledger-api-quarkus-workitems-ledger) for full schemas.

### Configuration

All ledger configuration is under `quarkus.workitems.ledger`. Defaults when the module is present:

```properties
# Master switch — set false to disable all ledger writes (default: true)
quarkus.workitems.ledger.enabled=true

# SHA-256 hash chain across entries for this WorkItem (default: true)
quarkus.workitems.ledger.hash-chain.enabled=true

# JSON snapshot of WorkItem state at each transition (default: true)
quarkus.workitems.ledger.decision-context.enabled=true

# Structured evidence fields per entry (default: false — opt-in)
quarkus.workitems.ledger.evidence.enabled=false

# Peer attestation endpoint active (default: true)
quarkus.workitems.ledger.attestations.enabled=true

# EigenTrust reputation scoring — nightly computation (default: false — opt-in)
quarkus.workitems.ledger.trust-score.enabled=false

# Trust-score-based routing suggestions via CDI events (default: false)
quarkus.workitems.ledger.trust-score.routing-enabled=false
```

### Enabling trust scores

Trust scores require accumulated ledger history to be meaningful. Enable only after the system has been running long enough for scores to stabilise:

```properties
quarkus.workitems.ledger.trust-score.enabled=true
```

A nightly scheduled job then computes EigenTrust-inspired scores from ledger history. After the first computation:

```bash
GET /workitems/actors/alice/trust
```

Returns the actor's current trust score, decision count, overturned count, and attestation tallies.

### Setting provenance

When an external system (Quarkus-Flow, CaseHub) creates a WorkItem on behalf of a process, call the provenance endpoint immediately after creation to link the WorkItem back to its source:

```bash
curl -X PUT "http://localhost:8080/workitems/{id}/ledger/provenance" \
  -H 'Content-Type: application/json' \
  -d '{
    "sourceEntityId": "workflow-instance-abc123",
    "sourceEntityType": "Flow:WorkflowInstance",
    "sourceEntitySystem": "quarkus-flow"
  }'
```

This sets `sourceEntityId`, `sourceEntityType`, and `sourceEntitySystem` on the initial `CREATED` ledger entry. Returns `409 Conflict` if provenance is already set.

---

## Section 7: Quarkus-Flow DSL (WorkItemsFlow)

The `quarkus-workitems-flow` module provides `WorkItemsFlow` — a base class that extends `Flow` with a `workItem()` DSL method. This is the preferred way to embed WorkItem suspension steps in a workflow definition, giving a uniform style alongside `function()`, `agent()`, and other quarkus-flow task types.

### Extending WorkItemsFlow

```java
@ApplicationScoped
public class DocumentApprovalWorkflow extends WorkItemsFlow {

    @Override
    public Workflow descriptor() {
        return workflow("document-approval")
            .tasks(
                workItem("legal-review")
                    .title("Legal review required")
                    .candidateGroups("legal-team")
                    .priority(WorkItemPriority.HIGH)
                    .payloadFrom((DocumentDraft d) -> d.toJson())
                    .buildTask(DocumentDraft.class)
            )
            .build();
    }
}
```

`workItem("legal-review")` returns a `WorkItemTaskBuilder`. Call:

| Method | Required | Description |
|---|---|---|
| `.title(String)` | yes | Human-readable title shown in the inbox |
| `.description(String)` | no | What the human needs to do |
| `.assigneeId(String)` | no | Direct assignee; mutually exclusive with `candidateGroups` |
| `.candidateGroups(String)` | no | Comma-separated groups eligible to claim |
| `.priority(WorkItemPriority)` | no | Defaults to `NORMAL` |
| `.payloadFrom(Function<T, String>)` | no | Extracts JSON context from the step's input |
| `.buildTask(Class<T>)` | yes | Builds the `FuncTaskConfigurer`; throws if `title` not set |

When the workflow reaches the `workItem()` step:
1. WorkItems creates a WorkItem with the configured parameters.
2. The step returns a `Uni<String>` that suspends the workflow.
3. A human or agent sees the WorkItem in `GET /inbox`, claims it, and completes it via `PUT /{id}/complete`.
4. The `Uni` resolves with the resolution JSON from the human, which becomes the next task's input.

If the WorkItem is rejected or cancelled, the `Uni` fails with `WorkItemResolutionException` and the workflow fails that step.

### Using HumanTaskFlowBridge directly

When you need programmatic control or cannot extend `WorkItemsFlow`, inject `HumanTaskFlowBridge` into any `Flow` subclass:

```java
@ApplicationScoped
public class ApprovalWorkflow extends Flow {

    @Inject
    HumanTaskFlowBridge workItemsBridge;

    @Override
    public Workflow descriptor() {
        return workflow("contract-approval")
            .tasks(
                function("await-legal-approval", (ContractInput input) ->
                    workItems.requestGroupApproval(
                        "Legal review required",
                        "Review contract before signing.",
                        "legal-team,senior-managers",
                        WorkItemPriority.CRITICAL,
                        input.toJson()
                    ), ContractInput.class)
            )
            .build();
    }
}
```

Both `requestApproval()` (direct assignee) and `requestGroupApproval()` (candidate groups) return `Uni<String>`. The returned string is the resolution JSON submitted by the human via `PUT /{id}/complete`.
