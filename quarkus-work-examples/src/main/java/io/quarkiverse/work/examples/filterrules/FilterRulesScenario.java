package io.quarkiverse.work.examples.filterrules;

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
import io.quarkiverse.work.runtime.filter.FilterRule;
import io.quarkiverse.work.runtime.model.AuditEntry;
import io.quarkiverse.work.runtime.model.WorkItem;
import io.quarkiverse.work.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.work.runtime.model.WorkItemPriority;
import io.quarkiverse.work.runtime.repository.AuditEntryStore;
import io.quarkiverse.work.runtime.service.WorkItemService;

/**
 * Scenario 6 — Dynamic Filter Rules: Auto-labelling Urgent WorkItems.
 *
 * <p>
 * Demonstrates the dynamic filter rule system: the procurement team configures a JEXL
 * rule that automatically applies the {@code urgent} label to any HIGH or CRITICAL priority
 * WorkItem at creation time. The rule is persisted in the database, survives restarts, and
 * can be disabled without a redeployment.
 *
 * <p>
 * Steps:
 * <ol>
 * <li>Create a dynamic filter rule that applies the {@code urgent} label when priority is HIGH
 * or CRITICAL.</li>
 * <li>Create a HIGH-priority WorkItem — the filter engine applies {@code urgent} automatically.</li>
 * <li>Create a NORMAL-priority WorkItem — no label is applied.</li>
 * <li>Verify labels on both WorkItems.</li>
 * <li>Delete the filter rule.</li>
 * </ol>
 *
 * <p>
 * Actors: {@code procurement-system} (creator).
 *
 * <p>
 * Endpoint: {@code POST /examples/filterrules/run}
 */
@Path("/examples/filterrules")
@Produces(MediaType.APPLICATION_JSON)
public class FilterRulesScenario {

    private static final Logger LOG = Logger.getLogger(FilterRulesScenario.class);

    private static final String SCENARIO_ID = "dynamic-filter-rules";
    private static final String ACTOR_CREATOR = "procurement-system";
    private static final String LABEL_URGENT = "urgent";

    @Inject
    WorkItemService workItemService;

    @Inject
    AuditEntryStore auditStore;

    /**
     * Run the dynamic filter rules scenario end to end and return the label verification results.
     *
     * @return scenario response with label check outcomes and combined audit trail
     */
    @POST
    @Path("/run")
    @Transactional
    public FilterRulesResponse run() {
        final List<StepLog> steps = new ArrayList<>();
        final int total = 5;

        // Step 1: create the dynamic filter rule
        final String description1 = "procurement-system creates dynamic filter rule: auto-label urgent for HIGH/CRITICAL priority";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 1, total, description1);

        final FilterRule rule = new FilterRule();
        rule.name = "auto-label-urgent";
        rule.description = "Applies 'urgent' label to any HIGH or CRITICAL priority WorkItem at creation";
        rule.enabled = true;
        rule.condition = "workItem.priority.name() == 'HIGH' || workItem.priority.name() == 'CRITICAL'";
        rule.events = "ADD";
        rule.actionsJson = "[{\"type\":\"APPLY_LABEL\",\"params\":{\"path\":\"urgent\",\"appliedBy\":\"auto-label-urgent\"}}]";
        rule.persist();
        steps.add(new StepLog(1, description1, null));

        // Step 2: create a HIGH-priority WorkItem — filter should apply "urgent" label
        final String description2 = "procurement-system creates HIGH-priority WorkItem: Emergency supplier payment";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 2, total, description2);

        final WorkItemCreateRequest highPriorityRequest = new WorkItemCreateRequest(
                "Emergency supplier payment — critical component shortage",
                "Supplier payment required within 24 hours to prevent production line stoppage",
                "procurement",
                null,
                WorkItemPriority.HIGH,
                null,
                "procurement-team",
                null,
                null,
                ACTOR_CREATOR,
                "{\"supplierId\": \"SUPP-001\", \"amount\": 12500.00, \"currency\": \"GBP\"}",
                null,
                null,
                null,
                null,
                null,
                null, null, null);

        final WorkItem highPriorityWi = workItemService.create(highPriorityRequest);
        steps.add(new StepLog(2, description2, highPriorityWi.id));

        // Step 3: create a NORMAL-priority WorkItem — filter should NOT apply "urgent" label
        final String description3 = "procurement-system creates NORMAL-priority WorkItem: Routine office supplies order";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 3, total, description3);

        final WorkItemCreateRequest normalPriorityRequest = new WorkItemCreateRequest(
                "Routine office supplies order — Q2 2026",
                "Quarterly office supplies requisition for the procurement team",
                "procurement",
                null,
                WorkItemPriority.NORMAL,
                null,
                "procurement-team",
                null,
                null,
                ACTOR_CREATOR,
                "{\"requisitionId\": \"REQ-2026-Q2-047\"}",
                null,
                null,
                null,
                null,
                null,
                null, null, null);

        final WorkItem normalPriorityWi = workItemService.create(normalPriorityRequest);
        steps.add(new StepLog(3, description3, normalPriorityWi.id));

        // Step 4: verify labels
        final String description4 = "Verify: HIGH-priority item has 'urgent' label; NORMAL-priority item does not";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 4, total, description4);

        final boolean urgentOnHigh = highPriorityWi.labels != null &&
                highPriorityWi.labels.stream().anyMatch(l -> LABEL_URGENT.equals(l.path));
        final boolean noUrgentOnNormal = normalPriorityWi.labels == null ||
                normalPriorityWi.labels.stream().noneMatch(l -> LABEL_URGENT.equals(l.path));

        steps.add(new StepLog(4, description4, null));

        // Step 5: delete the filter rule (clean up — demonstrates no-redeployment disable)
        final String description5 = "procurement-system deletes the auto-label-urgent filter rule";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 5, total, description5);
        FilterRule.deleteById(rule.id);
        steps.add(new StepLog(5, description5, null));

        // Collect audit trail across both WorkItems
        final List<AuditEntryResponse> auditTrail = new ArrayList<>();
        for (final WorkItem wi : List.of(highPriorityWi, normalPriorityWi)) {
            final List<AuditEntry> auditEntries = auditStore.findByWorkItemId(wi.id);
            auditEntries.stream()
                    .map(a -> new AuditEntryResponse(a.id, a.event, a.actor, a.detail, a.occurredAt))
                    .forEach(auditTrail::add);
        }

        return new FilterRulesResponse(
                SCENARIO_ID,
                steps,
                highPriorityWi.id,
                normalPriorityWi.id,
                urgentOnHigh,
                noUrgentOnNormal,
                auditTrail);
    }
}
