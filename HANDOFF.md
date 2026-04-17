# Quarkus WorkItems — Session Handover
**Date:** 2026-04-17

## Project Status

All planned phases complete. 315+ tests passing across all modules. 1 open issue (#39, blocked).

| Module | Tests |
|---|---|
| runtime | 217 |
| workitems-flow | 32 |
| quarkus-workitems-ledger | 74 |
| quarkus-workitems-queues | 50 |
| quarkus-workitems-examples | 4 |
| quarkus-workitems-queues-examples | 5 |
| quarkus-workitems-queues-dashboard | 14 (+ 6 disabled Pilot) |
| quarkus-workitems-flow-examples | 2 |
| testing | 16 |
| integration-tests | 19 (native) |

## What Was Built (recent sessions)

**WorkItemStore SPI (KV-native storage refactor):**
- `WorkItemStore` replaces `WorkItemRepository`: `put()`, `get()`, `scan(WorkItemQuery)`
- `WorkItemQuery` value object with static factories: `inbox()`, `expired()`, `claimExpired()`, `byLabelPattern()`, `all()` — backend-agnostic, aligned with SWF SDK conventions
- `AuditEntryStore` replaces `AuditEntryRepository`
- `JpaWorkItemStore`, `InMemoryWorkItemStore`, `JpaAuditEntryStore`, `InMemoryAuditEntryStore`
- Flyway V2001 (ledger module migration, renamed from V1002 to avoid conflict with quarkus-ledger)

**WorkItemExpressionEvaluator + ExpressionDescriptor:**
- `FilterConditionEvaluator` → `WorkItemExpressionEvaluator`: aligned with SWF ExpressionFactory naming
- `ExpressionDescriptor` record bundles language+expression — prevents mismatched pairs

**Labels + vocabulary (sub-epic #51):**
- `WorkItemLabel` @Embeddable on `WorkItem`: `path`, `persistence` (MANUAL/INFERRED), `appliedBy`
- `LabelVocabulary` + `LabelDefinition` (Panache entities, Flyway V2+V3)
- Vocabulary scopes: GLOBAL → ORG → TEAM → PERSONAL; seeded GLOBAL vocab with 7 common labels
- `findByLabelPattern()` in repo SPI: exact, `/*`, `/**` wildcard semantics
- REST: `GET /workitems?label=pattern`, `POST/DELETE /workitems/{id}/labels`, `GET/POST /vocabulary/{scope}`

**quarkus-workitems-queues (sub-epic #52):**
- `WorkItemFilter` entity + CRUD REST + ad-hoc eval (`POST /filters/evaluate`)
- `WorkItemExpressionEvaluator` SPI + `ExpressionDescriptor` (language+expression bundled): JEXL (default), JQ (jackson-jq), Lambda (CDI beans)
- `FilterChain`: filterId → Set<workItemId> inverse index for O(affected) cascade
- `FilterEngineImpl`: strip INFERRED → multi-pass eval (max 10) → propagation → cascade-correct delete
- `QueueView` + REST: `GET /queues/{id}` evaluates `additionalConditions` JEXL per item + sort
- `PUT /workitems/{id}/pickup`: queue pickup — PENDING (standard claim) or ASSIGNED+relinquishable (soft takeover, clears flag)
- `PUT /workitems/{id}/relinquishable`: signal willingness to release

**quarkus-workitems-queues-examples:**
- 5 runnable scenarios: support triage cascade, legal routing, finance approval, security escalation, document review pipeline (step-by-step with `POST /queue-examples/review/step`)

**quarkus-workitems-queues-dashboard:**
- Tamboui TUI inside Quarkus via `@QuarkusMain`; `@ObservesAsync WorkItemLifecycleEvent` — zero polling delay
- `QueueBoardBuilder` (pure logic, 10 unit tests), `ReviewStepService` (CDI step machine, 4 @QuarkusTest)
- Pilot end-to-end tests written + @Disabled (TestBackend not in Maven snapshots; needs `./gradlew publishToMavenLocal`)

## Immediate Next Step

**Dashboard verification** — run in a real terminal to confirm items move between columns:
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev -pl quarkus-workitems-queues-dashboard
```
Press `s` to step, `q` to quit.

## Open Issues

- #39 — ProvenanceLink PROV-O graph (blocked: CaseHub and Qhorus not ready)

## Remaining Deferred (queues module)

- Non-GLOBAL vocabulary scopes — `POST /vocabulary/ORG|TEAM|PERSONAL` returns 501 (needs auth context)

## References

| What | Path |
|---|---|
| Queues design spec | `docs/specs/2026-04-15-queues-design.md` |
| Queues ADR | `adr/0002-labels-as-queues-with-inferred-persistence.md` |
| API reference | `docs/api-reference.md` |
| Design tracker | `docs/DESIGN.md` |
| Ledger design | `docs/specs/ledger-design.md` |
