# Quarkus WorkItems — Session Handover
**Date:** 2026-04-16

## Project Status

All planned phases complete. 262 tests passing across all modules. 1 open issue (#39, blocked).

| Module | Tests |
|---|---|
| runtime | 217 |
| workitems-flow | 32 |
| quarkus-workitems-ledger | 74 |
| quarkus-workitems-queues | 45 |
| quarkus-workitems-examples | 4 |
| quarkus-workitems-flow-examples | 2 |
| testing | 16 |
| integration-tests | 19 (native) |

## What Was Built

**Labels + vocabulary (sub-epic #51):**
- `WorkItemLabel` @Embeddable on `WorkItem`: `path`, `persistence` (MANUAL/INFERRED), `appliedBy`
- `LabelVocabulary` + `LabelDefinition` (Panache entities, Flyway V2+V3)
- Vocabulary scopes: GLOBAL → ORG → TEAM → PERSONAL; seeded GLOBAL vocab with 7 common labels
- `findByLabelPattern()` in repo SPI: exact, `/*`, `/**` wildcard semantics
- REST: `GET /workitems?label=pattern`, `POST/DELETE /workitems/{id}/labels`, `GET/POST /vocabulary/{scope}`

**quarkus-workitems-queues (sub-epic #52):**
- `WorkItemFilter` entity + CRUD REST + ad-hoc eval (`POST /filters/evaluate`)
- `FilterConditionEvaluator` SPI: JEXL (default), JQ (jackson-jq), Lambda (CDI beans)
- JEXL/JQ contexts expose: status, priority, assigneeId, category, labels (as List<String>)
- `FilterChain`: filterId → Set<workItemId> inverse index for O(affected) cascade
- `FilterEngineImpl`: strip INFERRED → multi-pass eval (max 10) → propagation → cascade-correct delete
- `QueueView` entity + REST: `GET /queues/{id}` returns live label-pattern query with sort
- `WorkItemQueueState`: relinquishable soft-assignment flag (`PUT /workitems/{id}/relinquishable`)
- Flyway V2000 namespace (avoids conflicts with core V1-V3 and ledger V1002)

**Documentation sync:**
- `docs/api-reference.md`: added Label API, Vocabulary API, Filter API, Queue API, QueueState API; WorkItemLabelResponse schema
- `README.md`: queues in features, modules table, and documentation index
- `CLAUDE.md`: queues module in project structure and build commands
- `docs/DESIGN.md`: queues as Phase 7 (complete), updated module table and Phase 1 description

## Immediate Next Step

**Quarkiverse submission** — the extension is feature-complete, tested, native-validated. Path: fork quarkiverse org, wire CI (GitHub Actions, Renovate, docs site), open intake PR.

## Open Issues

- #39 — ProvenanceLink PROV-O graph (blocked: CaseHub and Qhorus not ready)

## Deferred (queues module)

- `additionalConditions` on `QueueView` — stored but not evaluated (O(n) evaluation per request, deferred)
- Relinquishable claim relaxation — flag stored, but `PUT /{id}/claim` does not yet check it
- Non-GLOBAL vocabulary scopes — `POST /vocabulary/ORG|TEAM|PERSONAL` returns 501 (needs auth context)

## References

| What | Path |
|---|---|
| Queues design spec | `docs/specs/2026-04-15-queues-design.md` |
| Queues ADR | `adr/0002-labels-as-queues-with-inferred-persistence.md` |
| API reference | `docs/api-reference.md` |
| Design tracker | `docs/DESIGN.md` |
| Ledger design | `docs/specs/ledger-design.md` |
