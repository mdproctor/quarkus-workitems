# Quarkus WorkItems — Examples Guide

The `quarkus-workitems-examples` module is a runnable Quarkus application that demonstrates every significant WorkItems feature through concrete business scenarios. Each scenario is a single HTTP call that plays out a full story, logs a step-by-step narrative to stdout, and returns the resulting audit trail as JSON.

The goal is not to show the API surface in abstract — it is to show how WorkItems solves specific problems that every real system eventually runs into: compliance evidence, AI/human handoffs, routing without redeployment, structured expiry, and so on.

---

## Running the Examples

### Start the application

```bash
# Build (first time or after any code change)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn install -DskipTests

# Start in dev mode — H2 in-memory database, no external dependencies
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev -pl quarkus-workitems-examples
```

Quarkus starts on `http://localhost:8080`. All scenarios are ready immediately — no seed data required, no configuration beyond defaults.

### Run a scenario

Each scenario has a dedicated endpoint:

```bash
curl -s -X POST http://localhost:8080/examples/{path}/run | jq .
```

The response is always a JSON object containing:
- `scenario` — the scenario identifier
- `steps` — ordered list of step descriptions and WorkItem IDs
- One or more of: `ledgerEntries`, `auditTrail`, `workItemIds`, `assignedTo`

### Run all scenarios in sequence

```bash
curl -s -X POST http://localhost:8080/examples/expense/run     | jq '.scenario, (.ledgerEntries | length)'
curl -s -X POST http://localhost:8080/examples/credit/run      | jq '.scenario, (.ledgerEntries | length)'
curl -s -X POST http://localhost:8080/examples/moderation/run  | jq '.scenario, (.ledgerEntries | length)'
curl -s -X POST http://localhost:8080/examples/queue/run       | jq '.scenario, (.allLedgerEntries | length)'
curl -s -X POST http://localhost:8080/examples/semantic/run    | jq '.scenario, .assignedTo'
curl -s -X POST http://localhost:8080/examples/cancel/run      | jq '.scenario, .finalStatus'
curl -s -X POST http://localhost:8080/examples/labels/run      | jq '.scenario, .labels'
curl -s -X POST http://localhost:8080/examples/vocabulary/run  | jq '.scenario, .vocabulary'
curl -s -X POST http://localhost:8080/examples/audit-search/run | jq '.scenario, (.auditEntries | length)'
curl -s -X POST http://localhost:8080/examples/form-schema/run | jq '.scenario, .formKey'
curl -s -X POST http://localhost:8080/examples/escalation/run  | jq '.scenario, .escalationFired'
curl -s -X POST http://localhost:8080/examples/filter-rules/run | jq '.scenario, .appliedLabels'
curl -s -X POST http://localhost:8080/examples/low-confidence/run | jq '.scenario, .flaggedCount'
curl -s -X POST http://localhost:8080/examples/queues/run      | jq '.scenario, .pickedUp'
```

### Run the test suite

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl quarkus-workitems-examples
```

Each scenario has a corresponding `@QuarkusTest` that asserts the key invariants without a running server.

---

## The Scenarios

---

### 1. Expense Approval — the baseline lifecycle

**Endpoint:** `POST /examples/expense/run`

**Why this matters:**
Without a state machine, any actor can complete any task at any time. A work item created by one system, half-reviewed by one person, and then completed by another — with no enforced sequencing — is an invitation for errors and disputes. WorkItems enforces create → claim → start → complete ordering with single-actor ownership at each stage. Nothing completes without being started; nothing starts without being claimed.

**What it demonstrates:**
- Full four-step lifecycle: `create` → `claim` → `start` → `complete`
- Automatic ledger recording at each transition (SHA-256 hash chain)
- `decisionContext` snapshot of WorkItem state per entry
- Resolution JSON attached at completion
- Audit trail alongside ledger entries

**The story:**
`finance-system` creates a HIGH priority expense report WorkItem for a team offsite. Alice claims it, starts reviewing, and approves it with a resolution JSON payload and a policy reference. The response returns four chained ledger entries — each digest referencing the previous — proving the sequence was unbroken.

**Key WorkItems features:**
- `workItemService.create(request)` → WorkItem in `PENDING`, ledger entry 1 written
- `workItemService.claim(id, "alice")` → status becomes `ASSIGNED`, entry 2 chained
- `workItemService.start(id, "alice")` → status becomes `IN_PROGRESS`, entry 3 chained
- `workItemService.complete(id, "alice", resolution, rationale, planRef)` → `COMPLETED`, entry 4 chained
- `ledgerEntries[*].digest` → SHA-256 of each entry; `ledgerEntries[*].previousHash` chains them

**When to use this pattern:**
Any business process that requires a single accountable human to review and act on something: expense approvals, purchase orders, access requests, change management tickets.

---

### 2. Credit Decision — compliance, delegation, and peer attestation

**Endpoint:** `POST /examples/credit/run`

**Why this matters:**
Regulated decisions need more than a completion event — they need to prove who reviewed what, when they paused and why, who they escalated to, and that a second pair of eyes signed off. GDPR Article 22 and the EU AI Act Article 12 both require explainability records for automated decision processes that affect individuals. Without this, compliance audits become manual reconstructions from scattered logs, emails, and memory.

**What it demonstrates:**
- `suspend` and `resume` for work paused pending external information
- `delegate` for escalation to a more senior actor
- Completion with `rationale` and `planRef` for GDPR/AI Act traceability
- Provenance linking WorkItem back to its originating system entity
- Peer attestation (dual-control) on the completion entry

**The story:**
`credit-scoring-system` creates a loan review WorkItem for LOAN-8821 and records provenance back to the credit engine. Officer Alice claims and starts the review, then suspends it while waiting for payslip documents. When documents arrive, she resumes and delegates the final decision to Supervisor Bob (case is too complex for her authority level). Bob claims, starts, and approves with a formal rationale and policy reference. Compliance Carol then attests the decision as `SOUND` — a dual-control sign-off required by the bank's credit policy.

**Key WorkItems features:**
- `workItemService.suspend(id, actor, reason)` → status `SUSPENDED`, detail recorded
- `workItemService.resume(id, actor)` → status returns to `IN_PROGRESS`
- `workItemService.delegate(id, fromActor, toActor)` → reassigns ownership, `PENDING` again
- `ledgerRepo.saveAttestation(attestation)` with `verdict=SOUND` → dual-control evidence
- Provenance supplement on the creation entry: `sourceEntityId`, `sourceEntityType`, `sourceEntitySystem`

**When to use this pattern:**
Credit decisions, insurance claims, loan origination, clinical trial approvals — any regulated process where proof of review path is a legal or audit requirement.

---

### 3. Content Moderation — AI/human hybrid with evidence capture

**Endpoint:** `POST /examples/moderation/run`

**Why this matters:**
Fully automated moderation misses edge cases and can't explain its decisions; fully manual moderation is too slow at scale. The confidence score creates a natural threshold: high-confidence AI decisions go straight through; low-confidence ones get a human review gate. But the AI still needs to record its evidence — what it saw, what it scored, what model version — so a human override can be assessed against the original signal, not a black box.

**What it demonstrates:**
- `actorType=AGENT` for AI-created WorkItems (via `agent:` prefix convention)
- Evidence capture on the creation ledger entry (confidence, model version, flag reason)
- Provenance from the AI system to the content event
- Human moderator rejection with formal rationale
- AI agent attestation (`ENDORSED`) on a human decision

**The story:**
`agent:content-ai` runs a classifier on POST-9912 and flags it for potential hate speech at 73% confidence. It creates a moderation WorkItem, recording the classifier evidence and provenance on the creation ledger entry. Moderator Dana claims, starts, and rejects the item — the context shows the post is satire, not hate speech — with a formal rationale. The `agent:compliance-bot` then attests the human override as `ENDORSED` at 88% confidence, closing the loop.

**Key WorkItems features:**
- `createdBy = "agent:content-ai"` → `actorType=AGENT` in ledger (prefix convention)
- `ComplianceSupplement.evidence` on the creation entry → classifier output as JSON
- `ProvenanceSupplement` → links to `POST-9912` in `content-ai` system
- `workItemService.reject(id, actor, reason, rationale)` → `REJECTED` status with explanation
- Attestation with `attestorType=AGENT` → AI bot sign-off on human decision

**When to use this pattern:**
Any AI-assisted triage where edge cases need human review: fraud flagging, medical image pre-screening, document classification, safety filtering.

---

### 4. Document Queue — group routing with accountability scoring

**Endpoint:** `POST /examples/queue/run`

**Why this matters:**
When work is pooled for a group to claim, there's no automatic accountability. Anyone can claim and release repeatedly without consequence. Trust scores surface who actually completes reliably — not just who claims the most tickets. Over time this shapes routing decisions and gives operations teams objective data on where bottlenecks are forming.

**What it demonstrates:**
- `candidateGroups` for group-based work pools
- `release` (un-claim without rejecting — work returns to the pool)
- Multiple actors building up separate ledger histories across several WorkItems
- EigenTrust reputation scoring computed from completion history
- Comparing trust scores between actors with different reliability records

**The story:**
`system:document-system` posts three document review WorkItems to the `doc-reviewers` candidate group. Reviewer Alice claims WI-1 but releases it — she can't review it now. Reviewer Bob picks it up and completes it, then claims and completes WI-2. Alice claims and completes WI-3. After all transitions complete, the trust score job runs. Bob scores higher: more completions, no releases. Alice's score reflects the release. Both scores are returned in the response.

**Key WorkItems features:**
- `candidateGroups = "doc-reviewers"` → WorkItem visible to all group members
- `workItemService.release(id, actor)` → returns to `PENDING`, visible in group queue again
- 14 total ledger entries across three WorkItems
- `trustScoreJob.runComputation()` → EigenTrust scores from ledger history
- `GET /workitems/actors/{actorId}/trust` → query trust score directly

**When to use this pattern:**
Any shared queue where multiple workers compete for work: document review, legal approvals, customer service escalations, bug triage.

---

### 5. NDA Review — semantic skill matching for automatic routing

**Endpoint:** `POST /examples/semantic/run`

**Why this matters:**
When you have many reviewers and a new task arrives, manually deciding who to assign wastes time and risks misallocation. A finance analyst reviewing an NDA and a legal specialist reviewing an expense report are both wrong assignments — but without skill-based routing, a human coordinator has to make that call every time. Semantic routing reads the task description and routes to the best-matched candidate automatically, without configuration rules per task type.

**What it demonstrates:**
- `WorkerSkillProfile` seeding for candidates (narrative-based skill descriptions)
- `SemanticWorkerSelectionStrategy` scoring profiles against WorkItem description
- Automatic pre-assignment at creation — no explicit assignee needed
- `candidateUsers` as the eligible pool; routing narrows to one
- Completed lifecycle on a pre-assigned WorkItem (no claim step — already `ASSIGNED`)

**The story:**
Two candidates are registered: `legal-specialist` (NDA, contracts, IP law) and `finance-analyst` (budgeting, expenses, accounting). `contract-agent` raises an NDA review task with both as `candidateUsers`. At creation, `SemanticWorkerSelectionStrategy` scores each candidate's skill narrative against the task description and pre-assigns `legal-specialist`. Legal-specialist starts the review immediately (no claim needed — already `ASSIGNED`) and completes with an approval decision.

**Key WorkItems features:**
- `WorkerSkillProfile.narrative` → free-text description of a worker's expertise
- `SemanticWorkerSelectionStrategy` → scores profiles at creation time, sets `assigneeId`
- `candidateUsers = "legal-specialist,finance-analyst"` → eligible pool for routing
- WorkItem emerges from `create()` with `status=ASSIGNED` and correct `assigneeId`
- `KeywordSkillMatcher` used in examples module (deterministic; no embedding model needed)

**When to use this pattern:**
Legal review routing, clinical assignment by specialty, support ticket routing by product expertise — wherever the task description contains enough signal to infer who should handle it.

---

### 6. Cancel — obsolescence without expiry clutter

**Endpoint:** `POST /examples/cancel/run`

**Why this matters:**
Cancel is distinct from reject. Reject means "I reviewed this and the answer is no." Cancel means "this task is no longer relevant — don't bother reviewing it." Without cancel, obsolete WorkItems sit in inboxes until they expire naturally, confusing workers who don't know whether to act on them, and inflating SLA metrics with tasks that should never have counted. Cancel closes the loop cleanly, with a machine-readable reason, without requiring any reviewer to touch it.

**What it demonstrates:**
- `cancel` transition directly from `PENDING` (no claim required)
- Reason recorded in the audit trail
- WorkItem status confirmed as `CANCELLED` in the response
- Cancellation by a system actor (not the original creator)

**The story:**
An employee requests a software license for a specific tool. The request is created as a WorkItem and routed to the IT procurement queue. Before any reviewer claims it, the IT system detects that the company just purchased a bulk license covering the entire department — the individual request is now redundant. The procurement system cancels the WorkItem with reason "Superseded by bulk license purchase BULK-2026-004." The response confirms `CANCELLED` status and the audit entry shows who cancelled it and why.

**Key WorkItems features:**
- `workItemService.cancel(id, actor, reason)` → `CANCELLED` from any pre-completion status
- Cancellation recorded as an audit entry with actor and reason
- No claim or start required — cancellation is a system-level override
- `finalStatus = CANCELLED` in response — calling system can stop polling

**When to use this pattern:**
Any process where an upstream business event makes an in-flight task moot: duplicate request detection, bulk policy changes that supersede individual approvals, workflow branches that were cancelled before their tasks were reached.

---

### 7. Labels — cross-cutting tags for VIP routing and filtering

**Endpoint:** `POST /examples/labels/run`

**Why this matters:**
Priority and category capture coarse-grained classification, but real workflows need cross-cutting tags: "this is urgent regardless of category", "this customer is VIP", "this is blocked on an external party". Hard-coding these into new status values or priority levels requires schema changes and deployments. Labels add that dimension at runtime — no migration, no code change, no enum extension.

**What it demonstrates:**
- Label application to an existing WorkItem
- Multiple labels on a single WorkItem (`priority/high`, `customer/vip`)
- Inbox filtering by label pattern (`customer/vip*`)
- Labels visible in the WorkItem response and filterable via query params

**The story:**
A VIP customer submits a support ticket. The ticket is created as a WorkItem with normal priority (the support system doesn't know which customers are VIP at creation time — that's resolved asynchronously from the CRM). The CRM integration applies two labels: `priority/high` and `customer/vip`. The scenario then queries the inbox filtered by `customer/vip*` and confirms the WorkItem appears. A support supervisor can now maintain a VIP-only view without any schema changes to the WorkItem model.

**Key WorkItems features:**
- `PUT /workitems/{id}/labels` → apply one or more labels
- Labels stored as a comma-separated field on the WorkItem
- `GET /workitems/inbox?labelPattern=customer/vip*` → pattern-matched label filtering
- Labels returned in the WorkItem JSON response as an array
- Applied after creation — the originating system does not need to know the labels at create time

**When to use this pattern:**
VIP customer routing, urgency tagging from a secondary system (e.g. SLA breach imminent), blocking/unblocking labels for dependency tracking, feature flags surfaced as labels.

---

### 8. Vocabulary — controlled terms for category consistency

**Endpoint:** `POST /examples/vocabulary/run`

**Why this matters:**
When ten teams create WorkItems with `category = "Annual Leave"`, `"annual_leave"`, `"AL"`, `"Holiday"`, downstream reporting, filter rules, and routing logic break silently. No error is thrown — the system accepts any string — but the data is wrong. Vocabulary is the contract that makes category a controlled term, not a free-text field. Register the valid terms once; reject or warn on anything else.

**What it demonstrates:**
- Vocabulary registration for a category namespace (`leave-type`)
- Listing registered vocabulary terms
- WorkItem creation with a vocabulary-controlled category value
- Validation behavior when a non-vocabulary category is submitted

**The story:**
The HR team registers standard leave categories before the leave management system goes live: `annual`, `sick`, `parental`, `compassionate`, `unpaid`. WorkItems created by the leave management system use these controlled terms in `category`. The scenario demonstrates registration, listing, and then creating two WorkItems — one with a valid term (`annual`) and one with a free-text value (`AL`) to show the validation response. Downstream, filter rules and audit queries work reliably because the category field is consistent.

**Key WorkItems features:**
- `POST /workitems/vocabulary` → register a term in a namespace
- `GET /workitems/vocabulary/{namespace}` → list all terms in a namespace
- Category value validated against vocabulary on WorkItem creation (if vocabulary configured)
- Validation error response with the registered valid terms listed

**When to use this pattern:**
HR systems (leave types, job grades), financial systems (cost centres, GL codes), operational systems (incident categories, support tiers) — anywhere consistent categorisation is required for reporting or routing.

---

### 9. Audit Search — cross-WorkItem compliance reconstruction

**Endpoint:** `POST /examples/audit-search/run`

**Why this matters:**
A single WorkItem's audit trail shows what happened to that item. But regulators ask "show me everything Alice did between January 1 and March 31" — that spans potentially hundreds of WorkItems. Without cross-WorkItem audit search, reconstructing an actor's activity requires manually joining tables or exporting to a separate analytics tool. The audit search API answers compliance questions directly, without bespoke queries.

**What it demonstrates:**
- Multiple WorkItems created across a time window
- Actions by a specific actor (`compliance-auditor`) across several items
- `GET /workitems/audit?actor=X&from=T1&to=T2` to retrieve cross-item audit entries
- Response showing all audit entries matching the filter, ordered by time
- Using audit search to reconstruct a compliance officer's review history

**The story:**
A compliance officer, `auditor-carol`, reviews several WorkItems over the course of a morning. The scenario creates five WorkItems in different categories, has Carol perform claim, start, and complete actions on three of them, and records notes on two. A compliance director then queries all of Carol's actions in a two-hour window via the audit search API. The response returns a chronological list of every action Carol took across all WorkItems — workItemId, event type, detail, and timestamp — sufficient to answer an audit question without accessing individual WorkItem records.

**Key WorkItems features:**
- `GET /workitems/audit?actor=auditor-carol&from=...&to=...` → time-bounded actor query
- Audit entries span multiple WorkItems in a single response
- Each entry includes `workItemId`, `event`, `actor`, `detail`, `occurredAt`
- No JPA joins or custom queries needed — the API handles the aggregation

**When to use this pattern:**
Regulatory compliance reporting, internal audit reconstruction, GDPR subject access requests that include human review actions, investigation of a specific actor's activity after an incident.

---

### 10. Form Schema — structured resolution contracts

**Endpoint:** `POST /examples/form-schema/run`

**Why this matters:**
If any JSON payload is valid, the resolution from a human could say `"done"`, `{"approved":true}`, or nothing at all. Without a schema, the calling system has to defensively parse whatever it receives and hope the reviewer understood what was needed. A JSON Schema defines the contract: what fields the resolution must capture, what types they must be, what values are valid. This enables dynamic UI generation and server-side validation in one step.

**What it demonstrates:**
- Form key registration with a JSON Schema definition
- WorkItem creation referencing a `formKey`
- `GET /workitems/forms/{formKey}` → retrieve the schema for a given form key
- Schema validation applied to resolution JSON at completion time
- Validation error when resolution does not match the schema

**The story:**
The legal team registers a JSON Schema for contract review WorkItems under the key `contract-review-v1`. The schema requires `outcome` (enum: `APPROVED`, `REJECTED`, `NEEDS_AMENDMENT`), `reviewedBy` (string), and `comments` (string, optional). WorkItems created with `formKey = "contract-review-v1"` advertise this schema. The scenario creates a contract review WorkItem, retrieves the form schema via the forms endpoint (which a UI would use to render the form), then demonstrates a valid completion and an invalid completion (missing required `outcome` field) to show the validation response.

**Key WorkItems features:**
- `POST /workitems/forms` → register a form key with its JSON Schema
- `GET /workitems/forms/{formKey}` → retrieve schema (used by dynamic form renderers)
- `formKey` field on WorkItem signals which form applies
- Resolution validated against schema at `complete` time; `400` with field errors on mismatch
- Enables frontend-agnostic form rendering from a single schema definition

**When to use this pattern:**
Legal review, compliance sign-offs, regulated approvals — any process where the resolution must capture specific structured data that downstream systems depend on, and where form UI should be generated from the same source of truth as the validation.

---

### 11. Escalation — SLA breach as an active event

**Endpoint:** `POST /examples/escalation/run`

**Why this matters:**
SLAs mean nothing if breaches go unnoticed until the next sprint review. When a WorkItem expires unclaimed, someone needs to know immediately — not in 48 hours when a manager checks a dashboard. Escalation converts time-based breaches into active CDI events that can trigger notifications, open incidents, or page on-call. Without this, expiry is a silent record-keeping detail; with it, expiry becomes an operational trigger.

**What it demonstrates:**
- WorkItem created with a very short `expiresAt` (set in the past)
- Expiry cleanup job triggered programmatically
- `EscalationPolicy.escalate(WorkLifecycleEvent)` called with `WorkEventType.EXPIRED`
- Audit entry written with `actorType=SYSTEM` and event type `WorkItemExpired`
- Observable escalation action recorded in the response

**The story:**
A production incident WorkItem is created with `expiresAt` set to one minute ago — simulating a scenario where it was created earlier and nobody claimed it. The expiry cleanup job is triggered (normally runs every 60 seconds). It finds the expired unclaimed item, writes a `WorkItemExpired` audit entry with `actor = "system:expiry-cleanup"`, and fires the escalation policy. The scenario records that escalation fired, and returns the expired WorkItem's final state and audit trail.

**Key WorkItems features:**
- `expiresAt` on WorkItem creation → defines when the expiry job notices the breach
- `ExpiryCleanupJob.runCleanup()` → programmatic trigger (scheduler runs it automatically in production)
- Audit entry: `event = "WorkItemExpired"`, `actor = "system:expiry-cleanup"`
- `EscalationPolicy` SPI receives the `WorkLifecycleEvent` → plug in Slack, PagerDuty, email
- `quarkus.workitems.cleanup.expiry-check-seconds` → controls scheduler interval

**When to use this pattern:**
Incident response SLAs, regulatory response deadlines, customer-facing acknowledgement targets — any situation where a missed deadline requires more than just a status update; it requires someone to act.

---

### 12. Filter Rules — runtime routing logic without redeployment

**Endpoint:** `POST /examples/filter-rules/run`

**Why this matters:**
Routing logic encoded in Java requires a redeployment to change. When a business analyst decides that HIGH and CRITICAL priority WorkItems should always carry an "urgent" label, that should be a configuration change — not a ticket, not a pull request, not a deployment window. JEXL filter rules let operators adjust auto-labelling, candidate overrides, and priority escalation at runtime via an API call. The logic lives in the database, not in the binary.

**What it demonstrates:**
- `POST /filter-rules` → register a JEXL condition + action pair
- Condition: `priority == 'HIGH' || priority == 'CRITICAL'`
- Action: `apply-label` → applies `"urgent"` to matching WorkItems
- Filter rule triggered automatically on lifecycle events
- WorkItems of different priorities processed — only high/critical get the label

**The story:**
The operations team registers a filter rule: if a WorkItem's priority is `HIGH` or `CRITICAL`, automatically apply the label `urgent`. Two WorkItems are then created — one with priority `NORMAL`, one with priority `HIGH`. After creation, the filter engine evaluates the rule against each WorkItem's lifecycle event. Only the HIGH priority item gets the `urgent` label. The response shows both WorkItems and confirms the label was applied selectively.

**Key WorkItems features:**
- `POST /filter-rules` body: `{"condition": "priority == 'HIGH' || priority == 'CRITICAL'", "action": "apply-label", "parameters": {"label": "urgent"}}`
- `FilterRegistryEngine` observes `WorkItemLifecycleEvent` and evaluates all active rules
- JEXL condition has access to all WorkItem fields (`title`, `category`, `priority`, `labels`, etc.)
- Rules are persistent — survive restarts — and editable without code change
- `GET /filter-rules` → list all active rules; `DELETE /filter-rules/{id}` → remove one

**When to use this pattern:**
Auto-labelling, auto-escalation of priority, candidate group override based on payload content, any routing or tagging logic that business users need to adjust without engineering involvement.

---

### 13. Low Confidence — systematic review gate for uncertain AI output

**Endpoint:** `POST /examples/low-confidence/run`

**Why this matters:**
AI systems are wrong sometimes. A confidence score is useful only if something acts on it. Without a systematic gate, a 51%-confidence AI decision gets treated the same as a 99%-confidence one. The low-confidence filter creates a review gate: uncertain AI tasks are flagged before a human processes them, reducing the blast radius of AI errors and making the human reviewer aware that extra scrutiny is warranted.

**What it demonstrates:**
- Multiple WorkItems created with varying `confidenceScore` values
- `LowConfidenceFilterProducer` applying `ai/low-confidence` label to items below threshold
- High-confidence items pass through unlabelled
- Low-confidence items can be filtered into a separate review queue via label pattern
- `confidenceScore` field on WorkItem creation request

**The story:**
An AI agent processes five contract review tasks overnight. Three are classified with high confidence (0.91, 0.87, 0.93) and two with lower confidence (0.62, 0.55). The scenario creates all five WorkItems with their respective confidence scores. After creation, the low-confidence filter runs and applies the `ai/low-confidence` label to the two below threshold (default: 0.75). A compliance reviewer queries the inbox filtered by `ai/low-confidence` and sees only the two uncertain items — ready for extra scrutiny before processing.

**Key WorkItems features:**
- `confidenceScore` field on `WorkItemCreateRequest` → stored on the WorkItem
- `LowConfidenceFilterProducer` → `FilterAction` that fires when `confidenceScore < threshold`
- `quarkus.workitems.ai.low-confidence-threshold` → configurable threshold (default 0.75)
- Applied label: `ai/low-confidence` → queryable via `GET /inbox?labelPattern=ai/low-confidence`
- High-confidence items are untouched — no label, no interruption

**When to use this pattern:**
AI-assisted triage where errors have meaningful cost: document classification, fraud flagging, medical pre-screening, content moderation — any pipeline where you want AI speed with a human backstop for uncertain cases.

---

### 14. Queue Module — specialist views, pickup, and relinquish

**Endpoint:** `POST /examples/queues/run`

**Why this matters:**
An unfiltered inbox grows without bound. Specialist workers don't want to wade through tasks outside their domain, and forcing them to do so means important items get buried. Queues give each worker a curated, filtered view of relevant work. Pickup and relinquish enable smooth handoffs: a worker can take a task from a shared queue and return it if they discover it needs different expertise, without the task being lost or reassigned to an inactive actor.

**What it demonstrates:**
- Queue definition with a JEXL condition (e.g. `category == 'legal'`)
- Multiple WorkItems — some matching the queue condition, some not
- `GET /queues/{name}` → filtered view showing only matching WorkItems
- Pickup: claiming a WorkItem via the queue endpoint
- Relinquish: returning a WorkItem to the queue without rejecting it
- Queue state endpoint confirming relinquishability

**The story:**
A `legal-documents` queue is defined with condition `category == 'legal'`. Five WorkItems are created across different categories: three legal, two finance. The legal queue returns only the three legal items. Legal specialist `lawyer-pat` picks up the first item via the queue pickup endpoint. After reading the detail, Pat realises the item requires external counsel and relinquishes it — the WorkItem returns to the queue, visible to other legal team members. The scenario confirms the queue count before and after relinquish and returns the final queue state.

**Key WorkItems features:**
- `POST /filters` → define a filter with JEXL condition and name
- `POST /queues` → create a named queue backed by a filter
- `GET /queues/{name}` → live filtered WorkItem list
- `PUT /workitems/{id}/pickup?actor=X&queue=legal-documents` → claim via queue
- `PUT /workitems/{id}/relinquish?actor=X` → return to queue without reject
- `GET /workitems/{id}/relinquishable` → check whether a WorkItem can be returned

**When to use this pattern:**
Legal review by document type, technical support routing by product area, clinical task routing by specialty, any domain with specialist workers who need a curated view of relevant-only work.

---

## Capability Coverage

| Capability | 1 Expense | 2 Credit | 3 Mod | 4 Queue | 5 Semantic | 6 Cancel | 7 Labels | 8 Vocab | 9 Audit | 10 Form | 11 Escalate | 12 Rules | 13 LowConf | 14 Queues |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| create / claim / start / complete | ✅ | ✅ | ✅ | ✅ | ✅ | | | | | | | | | ✅ |
| reject | | | ✅ | | | | | | | | | | | |
| cancel | | | | | | ✅ | | | | | | | | |
| delegate | | ✅ | | | | | | | | | | | | |
| suspend + resume | | ✅ | | | | | | | | | | | | |
| release | | | | ✅ | | | | | | | | | | |
| pickup + relinquish | | | | | | | | | | | | | | ✅ |
| candidateGroups | | | ✅ | ✅ | | | | | | | | | | ✅ |
| candidateUsers + semantic routing | | | | | ✅ | | | | | | | | | |
| labels | | | | | | | ✅ | | | | | ✅ | ✅ | |
| vocabulary | | | | | | | | ✅ | | | | | | |
| form schema | | | | | | | | | | ✅ | | | | |
| confidenceScore | | | | | ✅ | | | | | | | | ✅ | |
| expiry + escalation | | | | | | | | | | | ✅ | | | |
| JEXL filter rules | | | | | | | | | | | | ✅ | | ✅ |
| audit trail | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | | | ✅ | | ✅ | | | |
| cross-WorkItem audit search | | | | | | | | | ✅ | | | | | |
| hash chain (SHA-256) | ✅ | ✅ | ✅ | ✅ | | | | | | | | | | |
| provenance | | ✅ | ✅ | | | | | | | | | | | |
| peer attestation | | ✅ | ✅ | | | | | | | | | | | |
| actorType AGENT | | | ✅ | | | | | | | | | | | |
| EigenTrust trust scores | | | | ✅ | | | | | | | | | | |
