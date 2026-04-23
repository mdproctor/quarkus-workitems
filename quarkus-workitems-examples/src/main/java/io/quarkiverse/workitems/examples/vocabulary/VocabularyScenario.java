package io.quarkiverse.workitems.examples.vocabulary;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.logging.Logger;

import io.quarkiverse.workitems.examples.StepLog;
import io.quarkiverse.workitems.runtime.api.AuditEntryResponse;
import io.quarkiverse.workitems.runtime.model.AuditEntry;
import io.quarkiverse.workitems.runtime.model.LabelDefinition;
import io.quarkiverse.workitems.runtime.model.LabelVocabulary;
import io.quarkiverse.workitems.runtime.model.VocabularyScope;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.workitems.runtime.model.WorkItemPriority;
import io.quarkiverse.workitems.runtime.repository.AuditEntryStore;
import io.quarkiverse.workitems.runtime.service.LabelVocabularyService;
import io.quarkiverse.workitems.runtime.service.WorkItemService;

/**
 * Scenario 8 — HR Leave Vocabulary Registration.
 *
 * <p>
 * Demonstrates how a team registers its standard label vocabulary before creating
 * WorkItems, ensuring consistent category naming across all leave requests.
 *
 * <p>
 * Story: The HR team registers three leave categories in the TEAM-scoped vocabulary
 * before creating any WorkItems. This prevents "Annual Leave", "annual-leave", and
 * "Holiday" from being used interchangeably. Once the vocabulary is in place, an HR
 * manager creates leave request WorkItems using the registered categories and approves
 * the annual leave request.
 *
 * <p>
 * Actors:
 * <ul>
 * <li>{@code hr-admin} — registers the HR leave vocabulary entries</li>
 * <li>{@code hr-manager} — claims and approves the annual leave request</li>
 * </ul>
 *
 * <p>
 * Endpoint: {@code POST /examples/vocabulary/run}
 */
@Path("/examples/vocabulary")
@Produces(MediaType.APPLICATION_JSON)
public class VocabularyScenario {

    private static final Logger LOG = Logger.getLogger(VocabularyScenario.class);

    private static final String SCENARIO_ID = "hr-vocabulary";
    private static final String ACTOR_ADMIN = "hr-admin";
    private static final String ACTOR_MANAGER = "hr-manager";
    private static final String TEAM_ID = "hr-team";

    @Inject
    LabelVocabularyService vocabularyService;

    @Inject
    WorkItemService workItemService;

    @Inject
    AuditEntryStore auditStore;

    /**
     * Run the HR vocabulary registration scenario from end to end and return the result.
     *
     * @return scenario response containing steps, registered entry count, and audit trail
     */
    @POST
    @Path("/run")
    @Transactional
    public VocabularyResponse run() {
        final List<StepLog> steps = new ArrayList<>();
        final int total = 4;

        // Step 1: hr-admin registers three leave categories in the TEAM vocabulary
        final String description1 = "hr-admin registers leave vocabulary entries at TEAM scope";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 1, total, description1);

        final LabelVocabulary vocab = vocabularyService.findOrCreateVocabulary(
                VocabularyScope.TEAM, TEAM_ID, "HR Team Leave Vocabulary");

        vocabularyService.addDefinition(vocab.id, "leave/annual",
                "Standard annual leave entitlement", ACTOR_ADMIN);
        vocabularyService.addDefinition(vocab.id, "leave/sick",
                "Sick leave — self-certified or GP-certified", ACTOR_ADMIN);
        vocabularyService.addDefinition(vocab.id, "leave/parental",
                "Parental leave — maternity, paternity, or shared", ACTOR_ADMIN);

        // Count all definitions in this vocabulary
        final List<LabelDefinition> registered = LabelDefinition.findByVocabularyId(vocab.id);
        steps.add(new StepLog(1, description1, null));

        // Step 2: verify vocabulary via service list — count accessible entries
        final String description2 = "retrieve vocabulary and count accessible entries at TEAM scope";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 2, total, description2);
        final List<LabelDefinition> accessible = vocabularyService.listAccessible(VocabularyScope.TEAM);
        LOG.infof("[SCENARIO] %d vocabulary entries accessible at TEAM scope", accessible.size());
        steps.add(new StepLog(2, description2, null));

        // Step 3: create two WorkItems using the registered categories
        final String description3 = "hr-manager creates annual leave and sick leave WorkItems";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 3, total, description3);

        final WorkItemCreateRequest annualLeaveRequest = new WorkItemCreateRequest(
                "Annual leave request: Jane Smith — July 2026",
                "Annual leave for two weeks in July 2026",
                "leave/annual",
                null,
                WorkItemPriority.NORMAL,
                null,
                null,
                ACTOR_MANAGER,
                null,
                "jane.smith",
                "{\"startDate\": \"2026-07-14\", \"endDate\": \"2026-07-25\", \"days\": 10}",
                null,
                null,
                null,
                null,
                null);

        final WorkItem annualLeaveWi = workItemService.create(annualLeaveRequest);

        final WorkItemCreateRequest sickLeaveRequest = new WorkItemCreateRequest(
                "Sick leave notification: Tom Jones — 23 Apr 2026",
                "Self-certified sick leave — one day",
                "leave/sick",
                null,
                WorkItemPriority.LOW,
                null,
                null,
                ACTOR_MANAGER,
                null,
                "tom.jones",
                "{\"date\": \"2026-04-23\", \"selfCertified\": true}",
                null,
                null,
                null,
                null,
                null);

        workItemService.create(sickLeaveRequest);
        steps.add(new StepLog(3, description3, annualLeaveWi.id));

        // Step 4: hr-manager claims and approves the annual leave request
        final String description4 = "hr-manager claims and approves the annual leave request";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 4, total, description4);
        workItemService.claim(annualLeaveWi.id, ACTOR_MANAGER);
        workItemService.start(annualLeaveWi.id, ACTOR_MANAGER);
        workItemService.complete(annualLeaveWi.id, ACTOR_MANAGER,
                "{\"approved\": true, \"comment\": \"Sufficient leave balance; no conflicts\"}");
        steps.add(new StepLog(4, description4, annualLeaveWi.id));

        // Collect audit trail for the annual leave WorkItem
        final List<AuditEntry> auditEntries = auditStore.findByWorkItemId(annualLeaveWi.id);
        final List<AuditEntryResponse> auditTrail = auditEntries.stream()
                .map(a -> new AuditEntryResponse(a.id, a.event, a.actor, a.detail, a.occurredAt))
                .toList();

        return new VocabularyResponse(
                SCENARIO_ID,
                steps,
                registered.size(),
                annualLeaveWi.id,
                auditTrail);
    }
}
