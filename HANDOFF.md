# Quarkus WorkItems — Session Handover
**Date:** 2026-04-18

## Project Status

All planned phases complete. 321+ tests passing across all modules. 1 open issue (#39, blocked).

| Module | Tests |
|---|---|
| runtime | 217 |
| workitems-flow | 32 |
| quarkus-workitems-ledger | 74 |
| quarkus-workitems-queues | 50 |
| quarkus-workitems-examples | 4 |
| quarkus-workitems-queues-examples | 5 |
| quarkus-workitems-queues-dashboard | 20 (14 unit/CDI + 6 Pilot) |
| quarkus-workitems-flow-examples | 2 |
| testing | 16 |
| integration-tests | 19 (native) |

## What Was Built (recent sessions)

**WorkItemStore SPI (KV-native storage refactor):**
- `WorkItemStore` replaces `WorkItemRepository`: `put()`, `get()`, `scan(WorkItemQuery)`
- `WorkItemQuery` value object with static factories: `inbox()`, `expired()`, `claimExpired()`, `byLabelPattern()`, `all()`
- `AuditEntryStore` replaces `AuditEntryRepository`
- `JpaWorkItemStore`, `InMemoryWorkItemStore`, `JpaAuditEntryStore`, `InMemoryAuditEntryStore`
- Flyway V2001 (ledger module, renamed from V1002 to avoid conflict with quarkus-ledger)

**WorkItemExpressionEvaluator + ExpressionDescriptor (SWF-aligned):**
- `FilterConditionEvaluator` → `WorkItemExpressionEvaluator`
- `ExpressionDescriptor` record bundles language+expression — prevents mismatched pairs

**quarkus-ledger supplement API adaptation:**
- `LedgerEntry` fields moved to `ComplianceSupplement`, `ProvenanceSupplement` via `attach()`
- `ObservabilitySupplement` absent from published JAR — causedByEntryId/correlationId return null

**Tamboui Pilot tests enabled:**
- All 6 `QueueDashboardTest` Pilot tests passing headlessly
- `TestBackend` is in `tamboui-core:test-fixtures` (not `tamboui-tui:test-fixtures`)
- Feedback raised with Max Anderston (Tamboui author) re: placement/transitivity

**Labels + vocabulary (sub-epic #51), queues module (sub-epic #52), dashboard, examples:**
- *Unchanged — `git show HEAD~10:HANDOFF.md`*

## Immediate Next Step

**MongoDB backend** (`quarkus-workitems-persistence-mongodb`) — `WorkItemStore` + `WorkItemQuery` are now backend-agnostic; this is the first real test of the SPI redesign.

## Open Issues

- #39 — ProvenanceLink PROV-O graph (blocked: CaseHub and Qhorus not ready)

## Remaining Deferred

- Non-GLOBAL vocabulary scopes — `POST /vocabulary/ORG|TEAM|PERSONAL` returns 501 (needs auth context)

## References

| What | Path |
|---|---|
| API reference | `docs/api-reference.md` |
| Design tracker | `docs/DESIGN.md` |
| Queues design spec | `docs/specs/2026-04-15-queues-design.md` |
| Queues ADR | `adr/0002-labels-as-queues-with-inferred-persistence.md` |
| Blog (this session) | `blog/2026-04-18-mdp01-aligning-with-the-sdk.md` |
