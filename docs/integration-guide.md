# Quarkus Tarkus — Integration Guide

This guide covers four integration patterns: standalone REST, Quarkus-Flow workflow suspension, CDI lifecycle event observation, and custom escalation policies. A fifth section covers using the testing module for unit tests without a datasource.

---

## Section 1: Standalone REST Integration

Any system that can make HTTP requests integrates with Tarkus through its REST API. This covers curl scripts, frontend applications, other microservices, and non-Quarkus backends.

### Typical flow

1. **Create a WorkItem** — the calling system creates the item and records the returned `id`.
2. **Surface it to a human** — a frontend polls `GET /inbox` or the calling system notifies the assignee out-of-band.
3. **Human acts on it** — the frontend drives the claim/start/complete sequence.
4. **Poll for completion** — the calling system polls `GET /{id}` and inspects `status`.

### Complete curl example

```bash
#!/usr/bin/env bash
BASE=http://localhost:8080/tarkus/workitems

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
curl "http://localhost:8080/tarkus/workitems/inbox?assignee=bob"

# Show only high-priority items for the finance-team group
curl "http://localhost:8080/tarkus/workitems/inbox?candidateGroup=finance-team&priority=HIGH"
```

The response is a `WorkItemResponse[]` ordered by creation time. Frontends use `formKey` to decide which form component to render for each item, and `payload` to prepopulate the form with context.

---

## Section 2: Quarkus-Flow Integration

The `quarkus-tarkus-flow` module suspends a Quarkus-Flow workflow until a human resolves a WorkItem, then resumes the workflow with the resolution JSON.

### Dependency

```xml
<dependency>
  <groupId>io.quarkiverse.tarkus</groupId>
  <artifactId>quarkus-tarkus-flow</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

This pulls in `quarkus-tarkus` transitively.

### How it works

1. A flow task function calls `HumanTaskFlowBridge.requestApproval()` (or `requestGroupApproval()`).
2. The bridge creates a WorkItem via `WorkItemService` and registers a `CompletableFuture` in `PendingWorkItemRegistry`, keyed by the WorkItem's UUID.
3. The flow suspends on the returned `CompletableFuture`.
4. A human sees the WorkItem in `GET /inbox`, claims it, and completes it via `PUT /{id}/complete`.
5. `WorkItemService` fires a `WorkItemLifecycleEvent` with type `io.quarkiverse.tarkus.workitem.completed`.
6. `WorkItemFlowEventListener` observes the event and calls `PendingWorkItemRegistry.complete()`, resolving the `CompletableFuture` with the resolution JSON.
7. The flow resumes with the resolution as its output.

If the WorkItem is rejected, the future completes exceptionally with `WorkItemResolutionException`. If it is cancelled, the future also fails.

### Direct assignee example

```java
@ApplicationScoped
public class DocumentApprovalFlow {

    @Inject
    HumanTaskFlowBridge humanTask;

    public CompletableFuture<String> requestDocumentReview(String documentId) {
        return humanTask.requestApproval(
            "Review document for publication",              // title
            "Check for accuracy and approve or reject.",   // description
            "alice",                                       // direct assignee
            WorkItemPriority.HIGH,                         // priority
            "{\"documentId\": \"" + documentId + "\"}"    // payload (JSON context)
        );
    }
}
```

The `CompletableFuture<String>` resolves with the resolution JSON that `alice` submits when completing the WorkItem:

```bash
curl -X PUT "http://localhost:8080/tarkus/workitems/{id}/complete?actor=alice" \
  -H 'Content-Type: application/json' \
  -d '{"resolution": "{\"approved\": true, \"publishAt\": \"2026-04-15T09:00:00Z\"}"}'
```

The flow then receives `"{\"approved\": true, \"publishAt\": \"2026-04-15T09:00:00Z\"}"` as its result.

### Group routing example

Use `requestGroupApproval()` when you want any member of a group to be able to act, rather than a specific individual:

```java
@ApplicationScoped
public class ContractApprovalFlow {

    @Inject
    HumanTaskFlowBridge humanTask;

    public CompletableFuture<String> requestLegalReview(String contractId) {
        return humanTask.requestGroupApproval(
            "Legal review required",
            "Review contract " + contractId + " before signing.",
            "legal-team,senior-managers",   // comma-separated candidate groups
            WorkItemPriority.CRITICAL,
            "{\"contractId\": \"" + contractId + "\"}"
        );
    }
}
```

The WorkItem appears in the inbox for all members of `legal-team` and `senior-managers`. The first to claim it becomes the assignee; others no longer see it in their active inbox (though `GET /inbox?candidateGroup=legal-team` will still return it until claimed).

### Handling rejection and cancellation

```java
humanTask.requestApproval(title, description, assignee, priority, payload)
    .thenApply(resolution -> {
        // WorkItem was COMPLETED — resolution is the JSON string
        return parseDecision(resolution);
    })
    .exceptionally(ex -> {
        if (ex.getCause() instanceof WorkItemResolutionException wre) {
            // WorkItem was REJECTED or CANCELLED
            log.warnf("WorkItem %s not resolved: %s", wre.getWorkItemId(), wre.getMessage());
        }
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
        if (!"io.quarkiverse.tarkus.workitem.completed".equals(event.type())) {
            return;
        }
        notifications.send(event.actor(),
            "WorkItem " + event.workItemId() + " completed. Resolution: " + event.detail());
    }

    void onExpired(@Observes WorkItemLifecycleEvent event) {
        if (!"io.quarkiverse.tarkus.workitem.expired".equals(event.type())) {
            return;
        }
        notifications.alertAdmin("WorkItem " + event.workItemId() + " has expired!");
    }
}
```

### Event type reference

All event types follow the pattern `io.quarkiverse.tarkus.workitem.{action}` where `{action}` is the lowercase audit event name. See the [API Reference — Lifecycle event types](api-reference.md#lifecycle-event-types) for the full table.

### WorkItemLifecycleEvent fields

| Field | Type | Description |
|---|---|---|
| `type` | string | Full event type string (e.g. `io.quarkiverse.tarkus.workitem.completed`) |
| `source` | string | `/tarkus/workitems/{workItemId}` |
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

`@Alternative @Priority(1)` causes CDI to select your bean over Tarkus's default implementation. No `application.properties` change is needed for the bean selection itself, but the `quarkus.tarkus.escalation-policy` property is still read by the built-in policies — set it to `notify` (or any value) so the config validation passes if the built-in beans are still on the classpath.

```properties
quarkus.tarkus.escalation-policy=notify
quarkus.tarkus.claim-escalation-policy=notify
```

The expiry cleanup job (`ExpiryCleanupJob`) runs on the schedule configured by `quarkus.tarkus.cleanup.expiry-check-seconds` (default 60s). It calls your `EscalationPolicy` bean once per breached WorkItem per scan.

---

## Section 5: Unit Testing with quarkus-tarkus-testing

The `quarkus-tarkus-testing` module provides in-memory implementations of `WorkItemRepository` and `AuditEntryRepository`. These override the default JPA implementations via `@Alternative @Priority(1)`, so no datasource or Flyway configuration is needed in tests.

### Dependency

```xml
<dependency>
  <groupId>io.quarkiverse.tarkus</groupId>
  <artifactId>quarkus-tarkus-testing</artifactId>
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
    InMemoryWorkItemRepository repo;

    @Inject
    InMemoryAuditEntryRepository auditRepo;

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

    InMemoryWorkItemRepository workItemRepo = new InMemoryWorkItemRepository();
    InMemoryAuditEntryRepository auditRepo = new InMemoryAuditEntryRepository();
    TarkusConfig config = /* mock or test double */;

    WorkItemService service = new WorkItemService(workItemRepo, auditRepo, config);

    @BeforeEach
    void setUp() {
        workItemRepo.clear();
        auditRepo.clear();
    }

    // tests as above, no Quarkus boot time
}
```

This approach gives instant test execution — no Quarkus boot, no H2, no Flyway. Use `@QuarkusTest` only when you need CDI wiring or the REST layer.

### InMemoryWorkItemRepository behaviour notes

- Not thread-safe — designed for single-threaded test use only.
- `save()` assigns a random UUID if `workItem.id` is null.
- `findInbox()` matches using OR across `assigneeId`, `candidateGroups`, and `candidateUsers`, then applies AND for status, priority, category, and followUp filters — identical semantics to the JPA implementation.
- `findExpired()` returns items in `PENDING`, `ASSIGNED`, `IN_PROGRESS`, or `SUSPENDED` whose `expiresAt` is in the past.
- `findUnclaimedPastDeadline()` returns `PENDING` items whose `claimDeadline` is in the past.
- Candidate group matching uses exact comma-separated token comparison — `"bob"` does not match `"bobby"`.
