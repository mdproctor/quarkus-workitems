package io.quarkiverse.workitems.examples.labelling;

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
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.workitems.runtime.model.WorkItemPriority;
import io.quarkiverse.workitems.runtime.repository.AuditEntryStore;
import io.quarkiverse.workitems.runtime.repository.WorkItemQuery;
import io.quarkiverse.workitems.runtime.repository.WorkItemStore;
import io.quarkiverse.workitems.runtime.service.WorkItemService;

/**
 * Scenario 7 — Label Management.
 *
 * <p>
 * Demonstrates manual label lifecycle: add, query by pattern, and remove labels.
 *
 * <p>
 * Story: A customer support team receives a support ticket. The support bot creates
 * it with no labels. The team lead manually adds a priority label and a VIP customer
 * label. The inbox is queried by the {@code customer/*} label pattern to confirm the
 * ticket is surfaced. After the ticket is resolved, the VIP label is removed.
 *
 * <p>
 * Actors:
 * <ul>
 * <li>{@code support-bot} — creates the support ticket</li>
 * <li>{@code team-lead} — adds labels, claims, starts, completes, and removes the VIP label</li>
 * </ul>
 *
 * <p>
 * Endpoint: {@code POST /examples/labelling/run}
 */
@Path("/examples/labelling")
@Produces(MediaType.APPLICATION_JSON)
public class LabellingScenario {

    private static final Logger LOG = Logger.getLogger(LabellingScenario.class);

    private static final String SCENARIO_ID = "label-management";
    private static final String ACTOR_CREATOR = "support-bot";
    private static final String ACTOR_LEAD = "team-lead";
    private static final String LABEL_PRIORITY = "priority/high";
    private static final String LABEL_VIP = "customer/vip";

    @Inject
    WorkItemService workItemService;

    @Inject
    WorkItemStore workItemStore;

    @Inject
    AuditEntryStore auditStore;

    /**
     * Run the label management scenario from end to end and return the result.
     *
     * @return scenario response containing steps, labels at completion, match count, and audit trail
     */
    @POST
    @Path("/run")
    @Transactional
    public LabellingResponse run() {
        final List<StepLog> steps = new ArrayList<>();
        final int total = 6;

        // Step 1: support-bot creates the support ticket with no labels
        final String description1 = "support-bot creates support ticket for VIP client login failure";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 1, total, description1);

        final WorkItemCreateRequest request = new WorkItemCreateRequest(
                "Customer support: login failure for VIP client",
                "VIP client unable to log in since platform upgrade — urgent resolution required",
                "support",
                null,
                WorkItemPriority.NORMAL,
                null,
                null,
                null,
                null,
                ACTOR_CREATOR,
                "{\"clientId\": \"CLIENT-VIP-9912\", \"channel\": \"email\"}",
                null,
                null,
                null,
                null,
                null);

        final WorkItem wi = workItemService.create(request);
        steps.add(new StepLog(1, description1, wi.id));

        // Step 2: team-lead adds "priority/high" label
        final String description2 = "team-lead adds label 'priority/high' to escalate the ticket";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 2, total, description2);
        workItemService.addLabel(wi.id, LABEL_PRIORITY, ACTOR_LEAD);
        steps.add(new StepLog(2, description2, wi.id));

        // Step 3: team-lead adds "customer/vip" label because customer is VIP
        final String description3 = "team-lead adds label 'customer/vip' — client is on VIP tier";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 3, total, description3);
        workItemService.addLabel(wi.id, LABEL_VIP, ACTOR_LEAD);
        steps.add(new StepLog(3, description3, wi.id));

        // Step 4: query by label pattern "customer/*" and count matches
        final String description4 = "query inbox by label pattern 'customer/*' — ticket should appear";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 4, total, description4);
        final List<WorkItem> matching = workItemStore.scan(WorkItemQuery.byLabelPattern("customer/*"));
        final int matchCount = matching.size();
        steps.add(new StepLog(4, description4, wi.id));

        // Step 5: team-lead claims, starts, and completes the ticket
        final String description5 = "team-lead claims, starts, and completes the support ticket";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 5, total, description5);
        workItemService.claim(wi.id, ACTOR_LEAD);
        workItemService.start(wi.id, ACTOR_LEAD);
        workItemService.complete(wi.id, ACTOR_LEAD, "{\"resolved\": true, \"resolution\": \"Password reset issued\"}");
        steps.add(new StepLog(5, description5, wi.id));

        // Capture labels before removing the VIP label
        final WorkItem afterCompletion = workItemStore.get(wi.id).orElseThrow();
        final List<String> labelsAtCompletion = afterCompletion.labels.stream()
                .map(l -> l.path)
                .toList();

        // Step 6: team-lead removes "customer/vip" — MANUAL labels can be removed post-completion
        final String description6 = "team-lead removes 'customer/vip' label after issue resolved";
        LOG.infof("[SCENARIO] Step %d/%d: %s", 6, total, description6);
        workItemService.removeLabel(wi.id, LABEL_VIP);
        steps.add(new StepLog(6, description6, wi.id));

        // Collect audit trail
        final List<AuditEntry> auditEntries = auditStore.findByWorkItemId(wi.id);
        final List<AuditEntryResponse> auditTrail = auditEntries.stream()
                .map(a -> new AuditEntryResponse(a.id, a.event, a.actor, a.detail, a.occurredAt))
                .toList();

        return new LabellingResponse(
                SCENARIO_ID,
                steps,
                wi.id,
                labelsAtCompletion,
                matchCount,
                auditTrail);
    }
}
