# Quarkus WorkItems — Session Handover
**Date:** 2026-04-23

## Project Status

5 examples tests passing. Epic #122 complete.

| Module | Tests |
|---|---|
| quarkus-work-api | 23 |
| quarkus-work-core | 38 |
| runtime | 548 |
| quarkus-workitems-ai | 48 |
| quarkus-workitems-examples | 5 (was 4) |
| (others unchanged) | — |

## What Was Built This Session

**Epic #122 — Documentation and examples coverage (all 5 sub-tasks closed)**

**#E — Semantic routing example scenario**

`quarkus-workitems-examples/src/main/java/.../examples/semantic/`:
- `NdaReviewScenario` — POST /examples/semantic/run: seeds WorkerSkillProfile rows for legal-specialist and finance-analyst, creates NDA review WorkItem with both as candidateUsers, verifies routing to legal-specialist, starts and completes
- `KeywordSkillMatcher` — `@Alternative @Priority(2)`, keyword overlap scoring, overrides EmbeddingSkillMatcher, no external AI provider needed
- `SemanticRoutingResponse` — response record: scenario, steps, workItemId, assignedTo, resolvedBy, auditTrail
- `NdaReviewScenarioTest` — headless @QuarkusTest

Examples module pom: added `quarkus-workitems-ai` dependency + Jandex plugin on AI module.

**#B — Integration guide Section 9: semantic skill matching**
**#A — Integration guide Section 8: quarkus-work substrate SPI layer**
**#C — API reference: /worker-skill-profiles endpoints**
**#D — Primary spec updated** (EscalationPolicy signature, architecture diagram, build roadmap)

**Ledger drift repair (unplanned but necessary)**

Triggered by reinstalling `quarkus-ledger` sibling to fix a `LedgerMerkleFrontier.persist()` API change. Required:
- `JpaWorkItemLedgerEntryRepository` — rewritten to use EntityManager + JPQL (Panache statics gone from LedgerEntry/LedgerAttestation in new quarkus-ledger)
- `LedgerEventCapture` — LedgerMerkleFrontier ops migrated to EntityManager named queries
- `LedgerResource` — `findById` → `findEntryById` (renamed in LedgerEntryRepository interface)
- `quarkus-ledger/TrustScoreJob` + `JpaLedgerEntryRepository` — removed `@PersistenceUnit("qhorus")` (Qhorus-specific annotation was failing CDI validation in non-Qhorus contexts)
- Existing example tests — hash chain check updated: `previousHash` field removed from LedgerEntry (Merkle MMR owns chain now), tests now verify `digest` presence

## Immediate Next Step

Continue **Epic #100** — AI-suggested resolution:
`GET /workitems/{id}/resolution-suggestion` — LLM call with similar past completed WorkItems as context. Lives in `quarkus-workitems-ai`. Design: call WorkItemStore for N most similar completed items (same category, or embedding similarity), pass as few-shot examples to a ChatLanguageModel, return suggested resolution JSON.

## Priority Roadmap

*Unchanged — see `CLAUDE.md` Work Tracking section*

## Open Issues

| Status | Detail |
|---|---|
| #119 open | CompositeSkillProfileProvider — merge profiles from multiple sources (deferred) |
| #120 open | SemanticWorkerSelectionStrategy fallback to LeastLoaded on embedding failure (deferred) |
| #117 deferred | RoundRobinStrategy (stateful cursor) |
| #79, #39 blocked | External integrations and provenance |
| Ledger drift | `quarkus-workitems-ledger` tests (LedgerIntegrationTest etc.) still use old WorkItemCreateRequest 15-arg constructor — need updating. These tests in the ledger module itself, not the examples module. |

## References

| What | Path |
|---|---|
| Design tracker | `docs/DESIGN.md` |
| Integration guide (new §8, §9) | `docs/integration-guide.md` |
| API reference (new /worker-skill-profiles) | `docs/api-reference.md` |
| Primary spec (updated) | `docs/specs/2026-04-14-tarkus-design.md` |
| Semantic routing scenario | `quarkus-workitems-examples/src/main/java/.../examples/semantic/` |
| AI skill/ package | `quarkus-workitems-ai/src/main/java/io/quarkiverse/workitems/ai/skill/` |
| Epic priority table | `CLAUDE.md` Work Tracking section |
