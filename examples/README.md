# casehub-work-examples

Runnable scenario demonstrations for every ledger, audit, lifecycle, queue, AI,
and template capability of `casehub-work`. Each scenario runs in one HTTP call,
logs a narrative to stdout, and returns the result as JSON.

## Prerequisites

- Java 26: `export JAVA_HOME=$(/usr/libexec/java_home -v 26)`
- Maven (not the wrapper): `mvn`
- Build the parent first: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn install -DskipTests`

## Running

```bash
# Start in dev mode (H2 in-memory, auto-restart)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev -pl examples
```

Quarkus starts on `http://localhost:8080`. All 17 scenarios are ready immediately.

## Scenarios

| # | Scenario | Endpoint | Description |
|---|---|---|---|
| 1 | Expense Approval | `POST /examples/expense/run` | Baseline lifecycle (create, claim, start, complete), automatic ledger recording, SHA-256 hash chain, decisionContext snapshots |
| 2 | Regulated Credit Decision | `POST /examples/credit/run` | Provenance linking, suspend/resume, delegation with `causedByEntryId`, rationale + planRef (GDPR Art. 22), peer attestation (dual-control) |
| 3 | AI Content Moderation | `POST /examples/moderation/run` | Evidence capture, `actorType=AGENT` for AI creator, `actorType=HUMAN` for moderator, provenance from an AI system, agent attestation on a human decision |
| 4 | Document Review Queue | `POST /examples/queue/run` | `candidateGroups` work queue routing, inbox filtering, release (un-claim), multiple actors building ledger history, EigenTrust reputation scoring |
| 5 | Semantic Skill Routing | `POST /examples/semantic/run` | `SemanticWorkerSelectionStrategy` routes a WorkItem to the most qualified candidate based on skill profile matching against the work item description |
| 6 | Audit Search | `POST /examples/auditsearch/run` | Cross-WorkItem audit trail queries — query the audit store by actor, event type, and category to reconstruct an auditor's full action history |
| 7 | Software Licence Cancellation | `POST /examples/cancel/run` | Cancellation path — a WorkItem is created but cancelled before claim because the purchase is no longer needed; full audit trail of the cancel transition |
| 8 | Dynamic Filter Rules | `POST /examples/filterrules/run` | JEXL-based dynamic filter rules: auto-label HIGH/URGENT priority WorkItems; rules are persisted, survive restarts, and can be disabled without redeployment |
| 9 | Output Schema Validation | `POST /examples/formschema/run` | Register a template with `outputDataSchema` (JSON Schema), instantiate WorkItems, verify valid resolutions succeed and invalid ones are rejected |
| 10 | Label Management | `POST /examples/labelling/run` | Manual label lifecycle: add, query by pattern (`customer/*`), and remove labels on WorkItems |
| 11 | Low Confidence AI Filter | `POST /examples/lowconfidence/run` | `ai/low-confidence` permanent filter — AI-created WorkItems with confidence < 0.7 are auto-labelled for extra scrutiny; high-confidence and manual items pass unaffected |
| 12 | Exclusion Policy Demo | `POST /examples/exclusion-policy/run` | All parse branches of `ExpiringExclusionPolicy.check` — policy contract demo exercised directly, not via service-tier integration |
| 13 | Escalation | `POST /examples/escalation/run` | Expiry-triggered status transition — a WorkItem with a past `expiresAt` is processed by `ExpiryLifecycleService.checkExpired()`, confirming EXPIRED status and audit event |
| 14 | Business Hours SLA | `POST /examples/business-hours/run` | WorkItem deadlines set in business hours (not wall-clock hours); also has a `POST /examples/business-hours/preview` endpoint for SLA preview |
| 15 | Queue Module | `POST /examples/queues/run` | Label-pattern specialist queues with soft-claim handoff — pickup, relinquish, and senior takeover without force-reassignment |
| 16 | Subprocess Spawning | `POST /examples/spawn/run` | Parent WorkItem spawns parallel child WorkItems (credit-check, fraud-check, compliance-check), each with a distinct `callerRef` for orchestrator routing |
| 17 | Vocabulary Registration | `POST /examples/vocabulary/run` | Register a standard label vocabulary before creating WorkItems, ensuring consistent category naming across all work requests |
| 18 | Expense Compensation | `POST /examples/compensation/run` | Full compensation lifecycle — create, approve, trigger compensation, different actor reverses, auto-COMPENSATED. Audit trail for both original and compensating WorkItems |
| 19 | Loan Rollback | `POST /examples/loan-rollback/run` | Multi-step reverse-order compensation — 3 callerRef-correlated WorkItems compensated in dependency-reverse order with observable intermediate COMPENSATING state |
| 20 | Compensation Resilience | `POST /examples/compensation-resilience/run` | All 3 compensation guards (non-COMPLETED, double-compensation, compensator rejection) plus suspend/resume lifecycle on compensating WorkItem |

## Running All Scenarios in Sequence

```bash
curl -s -X POST http://localhost:8080/examples/expense/run | jq '.scenario'
curl -s -X POST http://localhost:8080/examples/credit/run | jq '.scenario'
curl -s -X POST http://localhost:8080/examples/moderation/run | jq '.scenario'
curl -s -X POST http://localhost:8080/examples/queue/run | jq '.scenario'
curl -s -X POST http://localhost:8080/examples/semantic/run | jq '.scenario'
curl -s -X POST http://localhost:8080/examples/auditsearch/run | jq '.scenario'
curl -s -X POST http://localhost:8080/examples/cancel/run | jq '.scenario'
curl -s -X POST http://localhost:8080/examples/filterrules/run | jq '.scenario'
curl -s -X POST http://localhost:8080/examples/formschema/run | jq '.scenario'
curl -s -X POST http://localhost:8080/examples/labelling/run | jq '.scenario'
curl -s -X POST http://localhost:8080/examples/lowconfidence/run | jq '.scenario'
curl -s -X POST http://localhost:8080/examples/exclusion-policy/run | jq '.scenario'
curl -s -X POST http://localhost:8080/examples/escalation/run | jq '.scenario'
curl -s -X POST http://localhost:8080/examples/business-hours/run | jq '.scenario'
curl -s -X POST http://localhost:8080/examples/queues/run | jq '.scenario'
curl -s -X POST http://localhost:8080/examples/spawn/run | jq '.scenario'
curl -s -X POST http://localhost:8080/examples/vocabulary/run | jq '.scenario'
curl -s -X POST http://localhost:8080/examples/compensation/run | jq '.scenario'
curl -s -X POST http://localhost:8080/examples/loan-rollback/run | jq '.scenario'
curl -s -X POST http://localhost:8080/examples/compensation-resilience/run | jq '.scenario'
```

## Other Lifecycle Transitions

`cancel` and expiry/escalation are also covered by named scenarios above (Cancel, Escalation),
but work via the standard WorkItem REST API as well:

```bash
# Create and immediately cancel a WorkItem
ID=$(curl -s -X POST http://localhost:8080/workitems \
  -H 'Content-Type: application/json' \
  -d '{"title":"Test cancel","createdBy":"demo"}' | jq -r '.id')
curl -s -X PUT "http://localhost:8080/workitems/$ID/cancel?actor=admin" \
  -H 'Content-Type: application/json' \
  -d '{"reason":"No longer needed"}' | jq .
```

Expiry + escalation: set `quarkus.work.default-expiry-hours=0` in
`application.properties` and wait for the scheduler. The ledger records
`WorkItemExpired`/`WorkItemEscalated` entries with `actorType=SYSTEM` (via the
`system:` prefix convention).

## Running the Tests

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl examples
```

Expected: 33 tests, 0 failures.
