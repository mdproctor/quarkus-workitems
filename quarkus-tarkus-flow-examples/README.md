# quarkus-tarkus-flow-examples

A runnable example showing the **TarkusFlow DSL** in a multi-step business workflow that
mixes automated machine steps with suspended human task steps.

Run the full scenario in one call:

```bash
curl -s -X POST http://localhost:8080/examples/flow/run | jq .
```

---

## The workflow

`ContractReviewWorkflow` extends `TarkusFlow` and defines a four-step contract approval pipeline:

```java
@ApplicationScoped
public class ContractReviewWorkflow extends TarkusFlow {

    @Override
    public Workflow descriptor() {
        return workflow("contract-review")
                .tasks(

                        // Step 1: automated — runs immediately, no human involved
                        fn("validate",
                                (Map submission) -> Map.of(
                                        "contractId", submission.get("contractId"),
                                        "party",      submission.get("party"),
                                        "value",      submission.get("value"),
                                        "status",     "validated"),
                                Map.class),

                        // Step 2: human — any member of legal-team can claim from the inbox
                        workItem("legalReview")
                                .title("Legal review: contract approval")
                                .description("Review contract terms for legal compliance. "
                                        + "Check liability clauses, IP ownership, and termination conditions.")
                                .candidateGroups("legal-team")
                                .priority(WorkItemPriority.HIGH)
                                .payloadFrom((Map draft) -> String.format(
                                        "{\"contractId\":\"%s\",\"party\":\"%s\",\"value\":\"%s\"}",
                                        draft.get("contractId"), draft.get("party"), draft.get("value")))
                                .buildTask(Map.class),

                        // Step 3: human — directly assigned to a named executive
                        workItem("executiveSignOff")
                                .title("Executive sign-off required")
                                .description("Legal team has approved. Final sign-off before execution.")
                                .assigneeId("exec-officer")
                                .priority(WorkItemPriority.CRITICAL)
                                .buildTask(String.class),

                        // Step 4: automated — records the execution after both approvals
                        fn("countersign",
                                (String executiveDecision) -> "EXECUTED — " + executiveDecision,
                                String.class))

                .build();
    }
}
```

### How it works

When `startInstance()` is called:

1. **validate** runs immediately — validates the contract fields and passes a draft map to the next step
2. **legalReview** — Quarkus-Flow suspends the workflow; a `WorkItem` appears in the Tarkus inbox with `candidateGroups=legal-team` and `priority=HIGH`; the full contract details are in `payload` via `payloadFrom()`; the workflow waits until a team member claims and completes it
3. **executiveSignOff** — the workflow suspends again; a `WorkItem` is assigned directly to `exec-officer` with `priority=CRITICAL`; the executive's resolution becomes the input to the next step
4. **countersign** runs immediately — records the execution with both approvals

No threads are blocked during suspension. The workflow coroutine parks and resumes when a human acts via the Tarkus REST API.

---

## DSL features demonstrated

| Feature | Where |
|---|---|
| `workItem("name")` | Both human steps |
| `.title(String)` | Both human steps — shown in the inbox |
| `.description(String)` | Both human steps — context for the reviewer |
| `.candidateGroups(String)` | legalReview — routes to any member of `legal-team` |
| `.assigneeId(String)` | executiveSignOff — assigned directly to `exec-officer` |
| `.priority(WorkItemPriority.HIGH)` | legalReview |
| `.priority(WorkItemPriority.CRITICAL)` | executiveSignOff |
| `.payloadFrom(fn)` | legalReview — extracts contract JSON from the workflow step input |
| `fn()` mixed with `workItem()` | validate + countersign steps |

---

## Running

### Prerequisites

```bash
# Java 26
export JAVA_HOME=$(/usr/libexec/java_home -v 26)

# Install upstream modules (once)
mvn install -DskipTests -pl runtime,deployment,testing,tarkus-flow
```

### Start in dev mode

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev -pl quarkus-tarkus-flow-examples
```

### Run the scenario

```bash
curl -s -X POST http://localhost:8080/examples/flow/run | jq .
```

The scenario runner starts the workflow, then simulates both human actors claiming and
completing their WorkItems. **Stdout** shows each step as it executes:

```
[FLOW] Workflow started — contract CTR-2024-001 for Acme Corp (£125,000)
[FLOW] Step 1: validate — automated, runs immediately
[FLOW] Step 2: legalReview — workflow suspended, waiting for legal-team...
[FLOW] Step 2: alice (legal-team) claims WorkItem <uuid>
[FLOW] Step 3: executiveSignOff — workflow suspended, waiting for exec-officer...
[FLOW] Step 3: exec-officer signs off on WorkItem <uuid>
[FLOW] Step 4: countersign — automated, recording execution
[FLOW] Workflow complete: "EXECUTED — {"signed":true,...}"
```

**Response JSON:**

```json
{
  "scenario": "contract-review",
  "steps": [
    "Workflow started: contract-review for CTR-2024-001 / Acme Corp",
    "Step 1 (validate): automated validation — no human involved",
    "Step 2 (legalReview): alice claimed from legal-team queue, approved with notes",
    "Step 3 (executiveSignOff): exec-officer signed off with authority reference",
    "Step 4 (countersign): automated — execution recorded",
    "Workflow complete: EXECUTED — ..."
  ],
  "workItemIds": [
    "<legal-review-workitem-uuid>",
    "<exec-signoff-workitem-uuid>"
  ],
  "finalResult": "EXECUTED — {\"signed\":true,\"authority\":\"CEO delegation ref CFO-2024-44\"}"
}
```

### Drive the workflow manually (without the scenario runner)

Start the workflow, then act on each WorkItem yourself via the Tarkus REST API:

```bash
# 1. Start the workflow (returns a workflow instance)
curl -s -X POST http://localhost:8080/examples/flow/start | jq .

# 2. Check the inbox for the legal-team WorkItem
curl -s "http://localhost:8080/tarkus/workitems/inbox?candidateGroup=legal-team" | jq .

# 3. Claim, start, and approve it
curl -s -X PUT "http://localhost:8080/tarkus/workitems/{id}/claim?claimant=alice"
curl -s -X PUT "http://localhost:8080/tarkus/workitems/{id}/start?actor=alice"
curl -s -X PUT "http://localhost:8080/tarkus/workitems/{id}/complete?actor=alice" \
  -H 'Content-Type: application/json' \
  -d '{"resolution":"{\"approved\":true,\"notes\":\"Terms acceptable\"}"}'

# 4. Check for the executive sign-off WorkItem
curl -s "http://localhost:8080/tarkus/workitems/inbox?assignee=exec-officer" | jq .

# 5. Complete the executive sign-off
curl -s -X PUT "http://localhost:8080/tarkus/workitems/{id}/claim?claimant=exec-officer"
curl -s -X PUT "http://localhost:8080/tarkus/workitems/{id}/start?actor=exec-officer"
curl -s -X PUT "http://localhost:8080/tarkus/workitems/{id}/complete?actor=exec-officer" \
  -H 'Content-Type: application/json' \
  -d '{"resolution":"{\"signed\":true}"}'
```

After step 5, the workflow resumes automatically and runs the countersign step.

---

## Running the tests

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl quarkus-tarkus-flow-examples
```

Expected: 2 tests, 0 failures.

---

## Using TarkusFlow in your own project

Add the dependency:

```xml
<dependency>
  <groupId>io.quarkiverse.tarkus</groupId>
  <artifactId>quarkus-tarkus-flow</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Extend `TarkusFlow` instead of `Flow`, and use `workItem("stepName")` wherever your workflow
needs to suspend for a human decision. See `quarkus-tarkus-flow/README.md` for the full API reference.
