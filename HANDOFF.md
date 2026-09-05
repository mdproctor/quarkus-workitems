# HANDOFF — casehub-work

## Session Summary

Completed #389, #390, and closed the entire saga compensation epic (#238, 9 issues). Implemented compensation visualization GraphQL APIs (#390), ran full work-end review (code review, 4-dimension branch audit, all sweeps), and began the merge-to-main process. Three of five repos merged successfully; engine and work repos have merge conflicts with main that need resolution.

## Completed This Session

### #390 — compensation visualization GraphQL APIs (engine repo)

Three GraphQL additions in `engine-graphql` module:

1. **`compensationGraph` field on `CaseDefinitionType`** — `CompensationGraphProjection.project(List<Binding>)` computes nodes (binding name, target type, isCompensation), edges (compensateRef links), and gaps (bindings without compensation coverage). Pure function, computed at query time.

2. **`compensationTimeline(caseId)` query** — joins PlanItemStore + EventLog + CaseDefinition to classify plan items as forward or compensation steps. Returns saga status, triggeredBy, reason, per-step progress. Uses CaseDefinition binding metadata to classify (no PlanItemRecord extension needed).

3. **`compensationChain(caseId)` query** — filters `CaseLedgerEntryRepository.findByCaseId()` by `"COMPENSATION"` key in `supplementJson`, extracts CompensationSupplement fields via Jackson. Added `casehub-engine-ledger` dependency to graphql pom.xml.

**Files created (engine repo):**
- `graphql/src/main/java/io/casehub/engine/graphql/CompensationGraphProjection.java`
- `graphql/src/main/java/io/casehub/engine/graphql/dto/CompensationGraphType.java`
- `graphql/src/main/java/io/casehub/engine/graphql/dto/CompensationNodeType.java`
- `graphql/src/main/java/io/casehub/engine/graphql/dto/CompensationEdgeType.java`
- `graphql/src/main/java/io/casehub/engine/graphql/dto/CompensationTimelineType.java`
- `graphql/src/main/java/io/casehub/engine/graphql/dto/TimelineStepType.java`
- `graphql/src/main/java/io/casehub/engine/graphql/dto/CompensationStepType.java`
- `graphql/src/main/java/io/casehub/engine/graphql/dto/CompensationChainType.java`
- `graphql/src/main/java/io/casehub/engine/graphql/dto/CompensationLedgerEntryType.java`
- `graphql/src/test/java/io/casehub/engine/graphql/CompensationGraphProjectionTest.java` (5 tests)
- `graphql/src/test/java/io/casehub/engine/graphql/CompensationTimelineQueryTest.java` (4 tests)
- `graphql/src/test/java/io/casehub/engine/graphql/CompensationChainQueryTest.java` (3 tests)

**Files modified (engine repo):**
- `graphql/src/main/java/io/casehub/engine/graphql/dto/CaseDefinitionType.java` — added `compensationGraph` field
- `graphql/src/main/java/io/casehub/engine/graphql/CaseQueryResolver.java` — added `compensationTimeline`, `compensationChain` queries + `ledgerRepository` injection
- `graphql/pom.xml` — added `casehub-engine-ledger` dependency

**Tests:** 40 total in graphql module, all green.

### Code review fix — V44 migration table name (work repo)

`V44__origin_ref.sql` referenced `work_items` (plural) but the table is `work_item` (singular). Fixed to match V43 and `WorkItemEntity @Table(name = "work_item")`.

### Protocol captured

`PP-20260904-66fb70` — "Non-domain UI components must be authored in pages or blocks-ui before app integration." Added to both FOUNDATION-INDEX and HARNESS-INDEX in the garden protocols.

### Follow-up issues filed

- **engine#1047** — extend CompensationGraphProjection with data flow edges (produces/consumes) for design-time viewer
- **engine#1048** — compensation GraphQL subscriptions + enriched timeline for ops dashboard
- **soredium#329** — close_artifacts.py slot workspace symlink resolution (fixed)
- **soredium#330** — conflict_resolved resets trajectory completion (fixed)
- **soredium#332** — is_stale() wipes .close-progress too aggressively (filed by other session)

### Diary entry

`casehubio.github.io/_notes/2026-09-04-compensation-visualization-apis.md` — committed and pushed.

## Merge Status — CRITICAL FOR NEXT SESSION

### Successfully merged to main and pushed to shared repos

| Repo | Squash commit | Status |
|------|---------------|--------|
| connectors | `4b48657` — infrastructure only, no code changes (D18) | merged, pushed |
| ledger | `3010f77` — CompensationSupplement (#383) | merged, pushed |
| qhorus | `e471eacd` — QhorusCompensationAdapter + JUDGMENT speech act (#388) | merged, pushed |

### Need merge conflict resolution before squash-merge

| Repo | Conflicted files | Key conflicts |
|------|-----------------|---------------|
| **engine** | 14 files | `CaseStatus.java` (compensation states vs main changes), `ActionGateApprovedHandler`/`ExpiredHandler`/`RejectedHandler`, `DefaultWorkerRuntime`, `JudgmentExpiredHandler`, examples (a2a, humantask, mcp, subcase), `JsonNodeForEachAdapter`, `DeadLetterReplayService` |
| **work** | 6 files | `HumanTaskScheduleHandler` (refactored on branch vs main changes), `WorkItemContextBuilder`, `HumanTaskPlannerIntegrationTest`, `decisions.md` (add/add), `.artifacts-promoted`, blog entry |

**Root cause:** Other branches landed on main in both repos while this epic was in progress. The branch point is old enough that main has diverged in files the epic also modified.

**Recommended approach:** Rebase `issue-238-saga-compensation` onto main in each repo first (resolve conflicts in the rebase), then squash-merge. Rebasing gives you conflict context per-commit, which is easier than resolving all conflicts at once in a squash merge.

**Shared repo stashes:** The ledger and qhorus shared repos had work stashed before pushing:
- `ledger`: stashed WIP on main (docs review work)
- `qhorus`: stashed WIP on main (display_order migration renumbering)
Run `git stash pop` in each shared repo after confirming main is clean.

## Slot State

All 5 repos on branch `issue-238-saga-compensation`, all pushed to shared clones:

| Repo | Branch pushed to shared | Main merged |
|------|------------------------|-------------|
| engine | yes | **no — 14 conflicts** |
| work | yes | **no — 6 conflicts** |
| connectors | yes | yes |
| ledger | yes | yes |
| qhorus | yes | yes |

## .plan State

Queue fully drained — all 9 issues checked off. `.plan` state is `closing:promoted`. `.close-progress` may be stale (soredium#332 was wiping it) — delete it and let work-end regenerate.

## Work-End Progress

Everything before squash/merge is done:
- [x] Code review (1 finding — V44 fix, applied)
- [x] Branch audit (conformance, coherence, structure, robustness — 0 findings)
- [x] Loose ends (0)
- [x] Forcing function (passed)
- [x] All sweeps (forage, protocol, update-claude-md, impl-doc-sync, doc-freshness, ADR, write-content)
- [x] Promotion (branches pushed to shared repos)
- [x] Trajectory
- [ ] **Squash — blocked on engine and work merge conflicts**
- [ ] Upstream push
- [ ] Branch stamp + close

## What's Next

1. **Resolve merge conflicts** in engine (14 files) and work (6 files) repos
2. **Squash-merge** both repos to main
3. **Push to upstream** (casehubio/engine, casehubio/work)
4. **Stamp and close** the branch in all 5 repos
5. **Pop stashes** in shared ledger and qhorus repos
6. **Push upstream** for connectors, ledger, qhorus (already on shared main, need upstream push)

## References

- Spec: `specs/issue-238-saga-compensation/2026-09-04-compensation-visualization-design.md`
- Decisions: `specs/issue-238-saga-compensation/decisions.md` (D1–D22)
- Plan: `plans/2026-09-04-compensation-visualization.md`
- Main spec: `specs/issue-238-saga-compensation/2026-09-01-saga-compensation-design.md`
