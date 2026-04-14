# Quarkus Tarkus — REST API Reference

Base path: `/tarkus/workitems`

All responses are `application/json`. All request bodies with a JSON body require `Content-Type: application/json`.

---

## Endpoints

### POST /tarkus/workitems

Creates a new WorkItem in `PENDING` status. If no `expiresAt` is supplied, the expiry is set to `now + quarkus.tarkus.default-expiry-hours`. If no `claimDeadline` is supplied and `quarkus.tarkus.default-claim-hours > 0`, the claim deadline is set to `now + quarkus.tarkus.default-claim-hours`.

**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `title` | string | yes | Human-readable task name |
| `description` | string | no | What the human needs to do |
| `category` | string | no | Classification label (e.g. `finance`, `legal`, `security-review`) |
| `formKey` | string | no | UI form reference — how frontends render this item |
| `priority` | WorkItemPriority | no | Defaults to `NORMAL` if omitted |
| `assigneeId` | string | no | Direct assignee; leave null to use candidate groups |
| `candidateGroups` | string | no | Comma-separated group names eligible to claim (e.g. `finance-team,managers`) |
| `candidateUsers` | string | no | Comma-separated user IDs individually invited to claim |
| `requiredCapabilities` | string | no | Comma-separated capability tags for routing |
| `createdBy` | string | no | System or agent that created the WorkItem |
| `payload` | string | no | JSON context for the human (stored as TEXT, not parsed) |
| `claimDeadline` | ISO-8601 instant | no | Must be claimed by; overrides config default |
| `expiresAt` | ISO-8601 instant | no | Must be completed by; overrides config default |
| `followUpDate` | ISO-8601 instant | no | Reminder date; surfaces in inbox when `followUp=true` |

**Response:** `201 Created`
**Headers:** `Location: /tarkus/workitems/{id}`
**Body:** `WorkItemResponse`

```bash
curl -X POST http://localhost:8080/tarkus/workitems \
  -H 'Content-Type: application/json' \
  -d '{
    "title": "Review contract for Acme Corp",
    "description": "Check section 4.2 for liability clause",
    "category": "legal",
    "priority": "HIGH",
    "candidateGroups": "legal-team",
    "createdBy": "contract-service",
    "payload": "{\"contractId\": \"CTR-9988\"}"
  }'
```

---

### GET /tarkus/workitems

Lists all WorkItems. Intended for admin use. Returns all records regardless of status or assignment.

**Response:** `200 OK`
**Body:** `WorkItemResponse[]`

```bash
curl http://localhost:8080/tarkus/workitems
```

---

### GET /tarkus/workitems/inbox

Returns WorkItems visible to the requesting user or group. Uses OR logic across `assigneeId`, `candidateGroups`, and `candidateUsers`; all additional filters (status, priority, category, followUp) are applied with AND logic.

If neither `assignee` nor `candidateGroup` is provided, returns all WorkItems filtered only by the secondary criteria.

**Query parameters:** see [Inbox query parameters](#inbox-query-parameters).

**Response:** `200 OK`
**Body:** `WorkItemResponse[]`

```bash
# Inbox for alice, showing only high-priority items
curl "http://localhost:8080/tarkus/workitems/inbox?assignee=alice&priority=HIGH"

# Group inbox for finance-team
curl "http://localhost:8080/tarkus/workitems/inbox?candidateGroup=finance-team&candidateGroup=managers"

# Items with a follow-up date due now or in the past
curl "http://localhost:8080/tarkus/workitems/inbox?assignee=alice&followUp=true"
```

---

### GET /tarkus/workitems/{id}

Returns a single WorkItem with its complete audit trail.

**Path parameter:** `id` — UUID

**Response:** `200 OK`
**Body:** `WorkItemWithAuditResponse`

**Error:** `404 Not Found` if the WorkItem does not exist.

```bash
curl http://localhost:8080/tarkus/workitems/a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

---

### PUT /tarkus/workitems/{id}/claim

Claims the WorkItem. Transitions `PENDING → ASSIGNED`. Sets `assigneeId` to the claimant and records `assignedAt`.

**Path parameter:** `id` — UUID
**Query parameter:** `claimant` — the user ID taking ownership

**Response:** `200 OK`
**Body:** `WorkItemResponse`

**Error:** `409 Conflict` if the WorkItem is not in `PENDING` status.

```bash
curl -X PUT "http://localhost:8080/tarkus/workitems/{id}/claim?claimant=alice"
```

---

### PUT /tarkus/workitems/{id}/start

Begins work on the WorkItem. Transitions `ASSIGNED → IN_PROGRESS`. Records `startedAt`.

**Path parameter:** `id` — UUID
**Query parameter:** `actor` — the user ID starting work

**Response:** `200 OK`
**Body:** `WorkItemResponse`

**Error:** `409 Conflict` if the WorkItem is not in `ASSIGNED` status.

```bash
curl -X PUT "http://localhost:8080/tarkus/workitems/{id}/start?actor=alice"
```

---

### PUT /tarkus/workitems/{id}/complete

Completes the WorkItem. Transitions `IN_PROGRESS → COMPLETED`. Stores the resolution JSON and records `completedAt`. Fires `io.quarkiverse.tarkus.workitem.completed` CDI event.

**Path parameter:** `id` — UUID
**Query parameter:** `actor` — the user ID completing the work
**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `resolution` | string | no | JSON decision from the human (stored as TEXT) |

**Response:** `200 OK`
**Body:** `WorkItemResponse`

**Error:** `409 Conflict` if the WorkItem is not in `IN_PROGRESS` status.

```bash
curl -X PUT "http://localhost:8080/tarkus/workitems/{id}/complete?actor=alice" \
  -H 'Content-Type: application/json' \
  -d '{"resolution": "{\"approved\": true, \"notes\": \"All checks passed\"}"}'
```

---

### PUT /tarkus/workitems/{id}/reject

Rejects the WorkItem. Transitions `ASSIGNED|IN_PROGRESS → REJECTED`. Records `completedAt`. Fires `io.quarkiverse.tarkus.workitem.rejected` CDI event.

**Path parameter:** `id` — UUID
**Query parameter:** `actor` — the user ID rejecting the work
**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `reason` | string | no | Human-readable rejection reason (stored in audit detail) |

**Response:** `200 OK`
**Body:** `WorkItemResponse`

**Error:** `409 Conflict` if the WorkItem is not in `ASSIGNED` or `IN_PROGRESS` status.

```bash
curl -X PUT "http://localhost:8080/tarkus/workitems/{id}/reject?actor=alice" \
  -H 'Content-Type: application/json' \
  -d '{"reason": "Insufficient documentation provided"}'
```

---

### PUT /tarkus/workitems/{id}/delegate

Delegates the WorkItem to another user. Transitions `ASSIGNED|IN_PROGRESS → PENDING` with the new assignee set and `delegationState=PENDING`. On first delegation, the actor becomes the `owner`. The actor's ID is appended to `delegationChain`.

**Path parameter:** `id` — UUID
**Query parameter:** `actor` — the user ID delegating
**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `to` | string | yes | The user ID to delegate to |

**Response:** `200 OK`
**Body:** `WorkItemResponse`

**Error:** `409 Conflict` if the WorkItem is not in `ASSIGNED` or `IN_PROGRESS` status.

```bash
curl -X PUT "http://localhost:8080/tarkus/workitems/{id}/delegate?actor=alice" \
  -H 'Content-Type: application/json' \
  -d '{"to": "bob"}'
```

---

### PUT /tarkus/workitems/{id}/release

Releases the WorkItem back to the candidate pool. Transitions `ASSIGNED → PENDING`. Clears `assigneeId`.

**Path parameter:** `id` — UUID
**Query parameter:** `actor` — the user ID releasing

**Response:** `200 OK`
**Body:** `WorkItemResponse`

**Error:** `409 Conflict` if the WorkItem is not in `ASSIGNED` status.

```bash
curl -X PUT "http://localhost:8080/tarkus/workitems/{id}/release?actor=alice"
```

---

### PUT /tarkus/workitems/{id}/suspend

Suspends the WorkItem. Transitions `ASSIGNED|IN_PROGRESS → SUSPENDED`. Records `suspendedAt` and saves the prior status in `priorStatus` for use by resume.

**Path parameter:** `id` — UUID
**Query parameter:** `actor` — the user ID suspending
**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `reason` | string | no | Why the item was suspended (stored in audit detail) |

**Response:** `200 OK`
**Body:** `WorkItemResponse`

**Error:** `409 Conflict` if the WorkItem is not in `ASSIGNED` or `IN_PROGRESS` status.

```bash
curl -X PUT "http://localhost:8080/tarkus/workitems/{id}/suspend?actor=alice" \
  -H 'Content-Type: application/json' \
  -d '{"reason": "Waiting for additional input from requester"}'
```

---

### PUT /tarkus/workitems/{id}/resume

Resumes a suspended WorkItem. Transitions `SUSPENDED → prior status` (either `ASSIGNED` or `IN_PROGRESS`). Clears `suspendedAt` and `priorStatus`.

**Path parameter:** `id` — UUID
**Query parameter:** `actor` — the user ID resuming

**Response:** `200 OK`
**Body:** `WorkItemResponse`

**Error:** `409 Conflict` if the WorkItem is not in `SUSPENDED` status.

```bash
curl -X PUT "http://localhost:8080/tarkus/workitems/{id}/resume?actor=alice"
```

---

### PUT /tarkus/workitems/{id}/cancel

Cancels the WorkItem. Transitions any non-terminal status → `CANCELLED`. Records `completedAt`. Fires `io.quarkiverse.tarkus.workitem.cancelled` CDI event. Admin operation.

**Path parameter:** `id` — UUID
**Query parameter:** `actor` — the user ID or system cancelling
**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `reason` | string | no | Cancellation reason (stored in audit detail) |

**Response:** `200 OK`
**Body:** `WorkItemResponse`

**Error:** `409 Conflict` if the WorkItem is already in a terminal status.

```bash
curl -X PUT "http://localhost:8080/tarkus/workitems/{id}/cancel?actor=admin" \
  -H 'Content-Type: application/json' \
  -d '{"reason": "Project cancelled by stakeholder"}'
```

---

## Schemas

### WorkItemResponse

Returned by all lifecycle endpoints and list endpoints.

| Field | Type | Description |
|---|---|---|
| `id` | UUID | Unique identifier |
| `title` | string | Human-readable task name |
| `description` | string | What the human needs to do |
| `category` | string | Classification label |
| `formKey` | string | UI form reference |
| `status` | WorkItemStatus | Current lifecycle status |
| `priority` | WorkItemPriority | `LOW`, `NORMAL`, `HIGH`, or `CRITICAL` |
| `assigneeId` | string | Current owner (actual worker) |
| `owner` | string | Ultimate responsible party; set on first delegation |
| `candidateGroups` | string | Comma-separated groups eligible to claim |
| `candidateUsers` | string | Comma-separated users individually invited to claim |
| `requiredCapabilities` | string | Comma-separated capability tags |
| `createdBy` | string | System or agent that created the WorkItem |
| `delegationState` | DelegationState | `null`, `PENDING`, or `RESOLVED` |
| `delegationChain` | string | Comma-separated prior assignees |
| `priorStatus` | WorkItemStatus | Status before suspension; `null` when not suspended |
| `payload` | string | JSON context for the human |
| `resolution` | string | JSON decision from the human; set on complete |
| `claimDeadline` | ISO-8601 instant | Must be claimed by this time |
| `expiresAt` | ISO-8601 instant | Must be completed by this time |
| `followUpDate` | ISO-8601 instant | Reminder date |
| `createdAt` | ISO-8601 instant | When the WorkItem was created |
| `updatedAt` | ISO-8601 instant | When the WorkItem was last modified |
| `assignedAt` | ISO-8601 instant | When it was claimed or assigned |
| `startedAt` | ISO-8601 instant | When `IN_PROGRESS` began |
| `completedAt` | ISO-8601 instant | When a terminal state was reached |
| `suspendedAt` | ISO-8601 instant | When `SUSPENDED` |

---

### WorkItemWithAuditResponse

Returned only by `GET /tarkus/workitems/{id}`. All fields of `WorkItemResponse`, plus:

| Field | Type | Description |
|---|---|---|
| `auditTrail` | AuditEntryResponse[] | Chronological list of lifecycle events for this WorkItem |

---

### AuditEntryResponse

| Field | Type | Description |
|---|---|---|
| `id` | UUID | Unique identifier of the audit entry |
| `event` | string | Audit event type (see [Lifecycle event types](#lifecycle-event-types)) |
| `actor` | string | Who triggered the event |
| `detail` | string | Optional JSON or text detail (reason, delegation target, resolution, etc.) |
| `occurredAt` | ISO-8601 instant | When the event was recorded |

---

## Inbox Query Parameters

Used with `GET /tarkus/workitems/inbox`.

| Parameter | Type | Description |
|---|---|---|
| `assignee` | string | Show WorkItems assigned to this user ID, or where this user is in `candidateUsers` |
| `candidateGroup` | string (repeatable) | Show WorkItems where any of these groups appears in `candidateGroups`. May be specified multiple times. |
| `candidateUser` | string | Alias for `assignee` — merged into the same OR filter |
| `status` | WorkItemStatus | Filter by exact status |
| `priority` | WorkItemPriority | Filter by exact priority |
| `category` | string | Filter by exact category string |
| `followUp` | boolean | If `true`, only return items where `followUpDate` is in the past (due for follow-up) |

Assignment filters (`assignee`, `candidateGroup`, `candidateUser`) are combined with OR. All other filters are applied with AND on top.

---

## WorkItemStatus Values

| Status | Terminal | Description |
|---|---|---|
| `PENDING` | no | Available for claiming; no active assignee |
| `ASSIGNED` | no | Claimed by an assignee; not yet started |
| `IN_PROGRESS` | no | Being actively worked |
| `COMPLETED` | yes | Successfully resolved by the human |
| `REJECTED` | no | Human declined or declared it uncompletable |
| `DELEGATED` | no | Transitional; ownership transferred, pending new assignment |
| `SUSPENDED` | no | On hold; will resume |
| `CANCELLED` | yes | Externally cancelled by system or admin |
| `EXPIRED` | no | Passed completion deadline; escalation policy fires |
| `ESCALATED` | yes | Escalation policy has fired; awaiting admin action or auto-resolved |

Note: `REJECTED` and `EXPIRED` are not terminal by the `isTerminal()` definition — `CANCELLED` on an expired or rejected item is still possible. `COMPLETED`, `REJECTED`, `CANCELLED`, and `ESCALATED` are the four terminal statuses per `WorkItemStatus.isTerminal()`.

---

## WorkItemPriority Values

| Value | Description |
|---|---|
| `LOW` | Below-normal urgency |
| `NORMAL` | Default priority |
| `HIGH` | Elevated urgency |
| `CRITICAL` | Requires immediate attention |

---

## Lifecycle Event Types

A `WorkItemLifecycleEvent` CDI event is fired on every transition. The `type` field follows the pattern `io.quarkiverse.tarkus.workitem.{action}`.

| Event type | Audit event | Triggered by |
|---|---|---|
| `io.quarkiverse.tarkus.workitem.created` | `CREATED` | `POST /` |
| `io.quarkiverse.tarkus.workitem.assigned` | `ASSIGNED` | `PUT /{id}/claim` |
| `io.quarkiverse.tarkus.workitem.started` | `STARTED` | `PUT /{id}/start` |
| `io.quarkiverse.tarkus.workitem.completed` | `COMPLETED` | `PUT /{id}/complete` |
| `io.quarkiverse.tarkus.workitem.rejected` | `REJECTED` | `PUT /{id}/reject` |
| `io.quarkiverse.tarkus.workitem.delegated` | `DELEGATED` | `PUT /{id}/delegate` |
| `io.quarkiverse.tarkus.workitem.released` | `RELEASED` | `PUT /{id}/release` |
| `io.quarkiverse.tarkus.workitem.suspended` | `SUSPENDED` | `PUT /{id}/suspend` |
| `io.quarkiverse.tarkus.workitem.resumed` | `RESUMED` | `PUT /{id}/resume` |
| `io.quarkiverse.tarkus.workitem.cancelled` | `CANCELLED` | `PUT /{id}/cancel` |
| `io.quarkiverse.tarkus.workitem.expired` | `EXPIRED` | Expiry cleanup job |
| `io.quarkiverse.tarkus.workitem.escalated` | `ESCALATED` | Escalation policy |

The `WorkItemLifecycleEvent` record fields: `type`, `source` (`/tarkus/workitems/{id}`), `subject` (UUID string), `workItemId` (UUID), `status` (post-transition), `occurredAt`, `actor`, `detail` (nullable JSON or reason string).

---

## Error Responses

| Status | Condition | Body |
|---|---|---|
| `404 Not Found` | WorkItem with the given `id` does not exist | `{"error": "WorkItem not found: {id}"}` |
| `409 Conflict` | Transition is not valid for the current status | `{"error": "Cannot {action} WorkItem in status: {STATUS}"}` |

---

## Ledger API (quarkus-tarkus-ledger)

These endpoints are only present when `quarkus-tarkus-ledger` is on the classpath. They activate automatically via CDI — no configuration required beyond adding the dependency.

---

### GET /tarkus/workitems/{id}/ledger

Returns all ledger entries for a WorkItem in sequence order, each with its peer attestations embedded.

**Path parameter:** `id` — UUID

**Response:** `200 OK`
**Body:** `LedgerEntryResponse[]`

**Error:** `404 Not Found` if the WorkItem does not exist.

```bash
curl http://localhost:8080/tarkus/workitems/a1b2c3d4-e5f6-7890-abcd-ef1234567890/ledger
```

---

### PUT /tarkus/workitems/{id}/ledger/provenance

Sets the source entity provenance on the creation ledger entry (sequence number 1). Call this immediately after creating a WorkItem from an external system (Quarkus-Flow, CaseHub, Qhorus) to record which entity originated the WorkItem.

**Path parameter:** `id` — UUID

**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `sourceEntityId` | string | yes | Identifier of the external entity, e.g. `"workflow-instance-abc123"` |
| `sourceEntityType` | string | yes | Type of the external entity, e.g. `"Flow:WorkflowInstance"` |
| `sourceEntitySystem` | string | yes | System owning the entity, e.g. `"quarkus-flow"` |

**Response:** `200 OK`

**Errors:**
- `404 Not Found` — WorkItem does not exist, or no creation ledger entry found.
- `409 Conflict` — Provenance is already set on the creation entry.

```bash
curl -X PUT "http://localhost:8080/tarkus/workitems/{id}/ledger/provenance" \
  -H 'Content-Type: application/json' \
  -d '{
    "sourceEntityId": "workflow-instance-abc123",
    "sourceEntityType": "Flow:WorkflowInstance",
    "sourceEntitySystem": "quarkus-flow"
  }'
```

---

### POST /tarkus/workitems/{id}/ledger/{entryId}/attestations

Posts a peer attestation on a specific ledger entry. Requires `quarkus.tarkus.ledger.attestations.enabled=true` (the default when the module is present).

**Path parameters:**
- `id` — WorkItem UUID
- `entryId` — ledger entry UUID to attest

**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `attestorId` | string | yes | Identity of the actor providing the attestation |
| `attestorType` | ActorType | yes | `HUMAN`, `AGENT`, or `SYSTEM` |
| `verdict` | AttestationVerdict | yes | Formal judgment on the ledger entry |
| `evidence` | string | no | Optional supporting evidence (JSON or free text) |
| `confidence` | double | yes | Confidence level in the range 0.0–1.0 |

**AttestationVerdict values:**

| Value | Meaning |
|---|---|
| `SOUND` | The decision was correct and well-reasoned |
| `FLAGGED` | The decision warrants further review |
| `ENDORSED` | The attestor positively endorses the decision |
| `CHALLENGED` | The attestor disputes the decision |

**Response:** `201 Created`

**Errors:**
- `404 Not Found` — ledger entry does not exist or does not belong to the given WorkItem.
- `409 Conflict` — attestations are disabled (`quarkus.tarkus.ledger.attestations.enabled=false`).

```bash
curl -X POST "http://localhost:8080/tarkus/workitems/{id}/ledger/{entryId}/attestations" \
  -H 'Content-Type: application/json' \
  -d '{
    "attestorId": "alice",
    "attestorType": "HUMAN",
    "verdict": "SOUND",
    "evidence": "{\"notes\": \"Decision was well-documented\"}",
    "confidence": 0.95
  }'
```

---

### GET /tarkus/actors/{actorId}/trust

Returns the computed EigenTrust-inspired trust score for an actor. Requires `quarkus.tarkus.ledger.trust-score.enabled=true`. Scores are computed by a nightly scheduled job; the endpoint returns `404` until the first computation run completes.

**Path parameter:** `actorId` — the actor's identity string (e.g. `"alice"`, `"agent-007"`)

**Response:** `200 OK`
**Body:** `ActorTrustScoreResponse`

**Errors:**
- `404 Not Found` — trust scoring is disabled, or no score has been computed yet for this actor.

```bash
curl http://localhost:8080/tarkus/actors/alice/trust
```

---

## Ledger Schemas

### LedgerEntryResponse

Returned by `GET /tarkus/workitems/{id}/ledger` as an array.

| Field | Type | Description |
|---|---|---|
| `id` | UUID | Ledger entry UUID |
| `workItemId` | UUID | WorkItem this entry belongs to |
| `sequenceNumber` | int | Position in the per-WorkItem ledger (1-based) |
| `entryType` | LedgerEntryType | Whether this is a `COMMAND`, `EVENT`, or other record type |
| `commandType` | string | The actor's expressed intent; `null` for pure events |
| `eventType` | string | The observable fact after execution; `null` for pure commands |
| `actorId` | string | Identity of the actor |
| `actorType` | ActorType | `HUMAN`, `AGENT`, or `SYSTEM` |
| `actorRole` | string | Functional role of the actor; `null` if not set |
| `planRef` | string | Policy or procedure reference governing this action; `null` if not set |
| `rationale` | string | The actor's stated basis for the decision; `null` if not set |
| `decisionContext` | string | JSON snapshot of the WorkItem state at this transition; `null` if `decision-context.enabled=false` |
| `evidence` | string | Structured evidence; `null` if `evidence.enabled=false` |
| `detail` | string | Free-text or JSON transition detail (reason, resolution, delegation target); `null` if not applicable |
| `causedByEntryId` | UUID | FK to the entry that caused this one; `null` if not applicable |
| `correlationId` | string | OpenTelemetry trace ID; `null` if not set |
| `sourceEntityId` | string | External entity identifier; `null` until provenance is set |
| `sourceEntityType` | string | Type of the external entity; `null` until provenance is set |
| `sourceEntitySystem` | string | System owning the external entity; `null` until provenance is set |
| `previousHash` | string | SHA-256 digest of the preceding entry; `null` for entry 1, or if hash chain disabled |
| `digest` | string | SHA-256 digest of this entry's canonical content; `null` if hash chain disabled |
| `occurredAt` | ISO-8601 instant | When this entry was recorded |
| `attestations` | LedgerAttestationResponse[] | Peer attestations on this entry (empty array if none) |

---

### LedgerAttestationResponse

Embedded in `LedgerEntryResponse.attestations`.

| Field | Type | Description |
|---|---|---|
| `id` | UUID | Attestation UUID |
| `ledgerEntryId` | UUID | Ledger entry this attestation targets |
| `workItemId` | UUID | WorkItem this attestation belongs to (denormalized) |
| `attestorId` | string | Identity of the attestor |
| `attestorType` | ActorType | `HUMAN`, `AGENT`, or `SYSTEM` |
| `attestorRole` | string | Functional role of the attestor; `null` if not set |
| `verdict` | AttestationVerdict | `SOUND`, `FLAGGED`, `ENDORSED`, or `CHALLENGED` |
| `evidence` | string | Supporting evidence; `null` if not provided |
| `confidence` | double | Confidence level in the range 0.0–1.0 |
| `occurredAt` | ISO-8601 instant | When the attestation was recorded |

---

### ActorTrustScoreResponse

Returned by `GET /tarkus/actors/{actorId}/trust`.

| Field | Type | Description |
|---|---|---|
| `actorId` | string | The actor's identity string |
| `actorType` | ActorType | `HUMAN`, `AGENT`, or `SYSTEM` |
| `trustScore` | double | Computed trust score in [0.0, 1.0]; neutral prior is 0.5 |
| `decisionCount` | int | Total number of `EVENT` ledger entries attributed to this actor |
| `overturnedCount` | int | Number of decisions that received at least one negative attestation |
| `attestationPositive` | int | Total count of positive attestations (`SOUND` or `ENDORSED`) received |
| `attestationNegative` | int | Total count of negative attestations (`FLAGGED` or `CHALLENGED`) received |
| `lastComputedAt` | ISO-8601 instant | When this score was last computed |
