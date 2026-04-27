package io.quarkiverse.work.examples.formschema;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.logging.Logger;

import io.quarkiverse.work.examples.StepLog;
import io.quarkiverse.work.runtime.api.AuditEntryResponse;
import io.quarkiverse.work.runtime.model.AuditEntry;
import io.quarkiverse.work.runtime.model.WorkItem;
import io.quarkiverse.work.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.work.runtime.model.WorkItemFormSchema;
import io.quarkiverse.work.runtime.model.WorkItemPriority;
import io.quarkiverse.work.runtime.repository.AuditEntryStore;
import io.quarkiverse.work.runtime.service.WorkItemService;

/**
 * Scenario 7 — Form Schema: register, retrieve, and delete a JSON Schema for WorkItems.
 *
 * <p>
 * A legal team registers a JSON Schema defining what information must appear in the payload
 * (contract type, parties, value) and what the resolution must contain (approved/rejected
 * decision, reviewer notes). This allows UI forms to be auto-generated without inspecting
 * source code.
 *
 * <p>
 * Steps: register schema → list by category → get by id → create conforming WorkItem →
 * complete with conforming resolution → delete schema.
 *
 * <p>
 * Actors: {@code legal-admin} (schema creator), {@code legal-reviewer} (WorkItem completer).
 *
 * <p>
 * Endpoint: {@code POST /examples/formschema/run}
 */
@Path("/examples/formschema")
@Produces(MediaType.APPLICATION_JSON)
public class FormSchemaScenario {

    private static final Logger LOG = Logger.getLogger(FormSchemaScenario.class);

    private static final String SCENARIO_ID = "form-schema";
    private static final String ACTOR_ADMIN = "legal-admin";
    private static final String ACTOR_REVIEWER = "legal-reviewer";
    private static final String CATEGORY = "contract-review";
    private static final String SCHEMA_NAME = "Contract Review Form";

    private static final String PAYLOAD_SCHEMA = """
            {
              "type": "object",
              "required": ["contractType", "parties", "contractValue"],
              "properties": {
                "contractType": { "type": "string" },
                "parties": { "type": "array", "items": { "type": "string" }, "minItems": 2 },
                "contractValue": { "type": "number", "minimum": 0 }
              }
            }
            """;

    private static final String RESOLUTION_SCHEMA = """
            {
              "type": "object",
              "required": ["decision", "reviewerNotes"],
              "properties": {
                "decision": { "type": "string", "enum": ["APPROVED", "REJECTED"] },
                "reviewerNotes": { "type": "string" }
              }
            }
            """;

    @Inject
    WorkItemService workItemService;

    @Inject
    AuditEntryStore auditStore;

    /**
     * Run the form schema scenario end to end and return the outcome.
     *
     * @return scenario response confirming schema registration, WorkItem completion, and schema deletion
     */
    @POST
    @Path("/run")
    @Transactional
    public FormSchemaResponse run() {
        final List<StepLog> steps = new ArrayList<>();
        final int total = 6;

        // Step 1: register the form schema
        final String description1 = "legal-admin registers a JSON Schema for contract-review WorkItems";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 1, total, description1);
        final WorkItemFormSchema schema = new WorkItemFormSchema();
        schema.name = SCHEMA_NAME;
        schema.category = CATEGORY;
        schema.payloadSchema = PAYLOAD_SCHEMA;
        schema.resolutionSchema = RESOLUTION_SCHEMA;
        schema.schemaVersion = "1.0";
        schema.createdBy = ACTOR_ADMIN;
        schema.persist();
        steps.add(new StepLog(1, description1, null));

        // Step 2: list schemas by category and verify ours is there
        final String description2 = "List schemas by category=contract-review — verify schema present";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 2, total, description2);
        final List<WorkItemFormSchema> byCategory = WorkItemFormSchema.findByCategory(CATEGORY);
        if (byCategory.stream().noneMatch(s -> SCHEMA_NAME.equals(s.name))) {
            throw new IllegalStateException("Schema not found in category listing after persist");
        }
        steps.add(new StepLog(2, description2 + " — found " + byCategory.size() + " schema(s)", null));

        // Step 3: retrieve schema by id
        final String description3 = "Retrieve schema by id — confirm name and version";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 3, total, description3);
        final WorkItemFormSchema found = WorkItemFormSchema.findById(schema.id);
        if (found == null || !SCHEMA_NAME.equals(found.name)) {
            throw new IllegalStateException("Schema not found by id: " + schema.id);
        }
        steps.add(new StepLog(3, description3 + " — schema v" + found.schemaVersion + " confirmed", null));

        // Step 4: create a WorkItem with a conforming payload
        final String description4 = "Create a contract-review WorkItem with a conforming payload";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 4, total, description4);
        final WorkItemCreateRequest request = new WorkItemCreateRequest(
                "Review Services Agreement: TechCorp Ltd",
                "Annual SaaS services agreement requiring legal sign-off",
                CATEGORY,
                null,
                WorkItemPriority.HIGH,
                ACTOR_REVIEWER,
                null,
                null,
                null,
                ACTOR_ADMIN,
                "{\"contractType\": \"SaaS Services Agreement\", \"parties\": [\"Acme Corp\", \"TechCorp Ltd\"], \"contractValue\": 48000}",
                null,
                null,
                null,
                null,
                null,
                null, null, null);
        final WorkItem wi = workItemService.create(request);
        steps.add(new StepLog(4, description4, wi.id));

        // Step 5: legal-reviewer claims, starts, and completes the WorkItem with a conforming resolution
        final String description5 = "legal-reviewer claims, starts, and completes the WorkItem with a conforming resolution";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 5, total, description5);
        workItemService.claim(wi.id, ACTOR_REVIEWER);
        workItemService.start(wi.id, ACTOR_REVIEWER);
        workItemService.complete(
                wi.id,
                ACTOR_REVIEWER,
                "{\"decision\": \"APPROVED\", \"reviewerNotes\": \"Standard terms; no amendments required. IP clauses reviewed.\"}");
        steps.add(new StepLog(5, description5, wi.id));

        // Step 6: delete the schema (cleanup after scenario)
        final String description6 = "Delete the form schema (scenario cleanup)";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 6, total, description6);
        final boolean deleted = WorkItemFormSchema.deleteById(schema.id);
        steps.add(new StepLog(6, description6 + " — deleted=" + deleted, null));

        // Collect audit trail
        final List<AuditEntry> auditEntries = auditStore.findByWorkItemId(wi.id);
        final List<AuditEntryResponse> auditTrail = auditEntries.stream()
                .map(a -> new AuditEntryResponse(a.id, a.event, a.actor, a.detail, a.occurredAt))
                .toList();

        return new FormSchemaResponse(
                SCENARIO_ID,
                steps,
                schema.id,
                schema.name,
                wi.id,
                deleted,
                auditTrail);
    }
}
