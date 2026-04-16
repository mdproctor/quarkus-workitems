package io.quarkiverse.workitems.examples.queues.security;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.logging.Logger;

import io.quarkiverse.workitems.examples.queues.QueueScenarioResponse;
import io.quarkiverse.workitems.examples.queues.QueueScenarioStep;
import io.quarkiverse.workitems.queues.model.FilterAction;
import io.quarkiverse.workitems.queues.model.FilterScope;
import io.quarkiverse.workitems.queues.model.WorkItemFilter;
import io.quarkiverse.workitems.runtime.model.LabelPersistence;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.workitems.runtime.model.WorkItemPriority;
import io.quarkiverse.workitems.runtime.repository.WorkItemRepository;
import io.quarkiverse.workitems.runtime.service.WorkItemService;

/**
 * Scenario: Multi-Label Security Escalation.
 *
 * <p>
 * Demonstrates multi-label stacking and cascade escalation inspired by
 * Zendesk + PagerDuty dual-escalation patterns:
 * <ul>
 * <li>Filter A: {@code category == 'security'} → {@code security/incident}</li>
 * <li>Filter B: {@code priority == 'CRITICAL'} → {@code priority/critical}</li>
 * <li>Filter C (cascade): {@code labels.contains('security/incident') && labels.contains('priority/critical')}
 * → {@code security/exec-escalate}</li>
 * </ul>
 *
 * <p>
 * A HIGH security incident gets only {@code security/incident}.
 * A CRITICAL security incident gets {@code security/incident} + {@code priority/critical} +
 * (cascade) {@code security/exec-escalate} — the executive escalation queue fires only
 * when BOTH conditions are true simultaneously.
 *
 * <p>
 * This pattern is only possible with label-based cascade routing — a single filter
 * cannot express the same logic without the intermediate labels.
 *
 * <p>
 * Endpoint: {@code POST /queue-examples/security/run}
 */
@Path("/queue-examples/security")
@Produces(MediaType.APPLICATION_JSON)
public class SecurityEscalationScenario {

    private static final Logger LOG = Logger.getLogger(SecurityEscalationScenario.class);

    @Inject
    WorkItemService workItemService;

    @Inject
    WorkItemRepository workItemRepo;

    private void setupFilters() {
        if (WorkItemFilter.count("name", "Security-A: Incident Detection") > 0)
            return;

        // Filter A: all security category items → security/incident
        final WorkItemFilter filterA = new WorkItemFilter();
        filterA.name = "Security-A: Incident Detection";
        filterA.scope = FilterScope.ORG;
        filterA.conditionLanguage = "jexl";
        filterA.conditionExpression = "category == 'security'";
        filterA.actions = WorkItemFilter.serializeActions(List.of(
                FilterAction.applyLabel("security/incident")));
        filterA.active = true;
        filterA.persist();

        // Filter B: all CRITICAL items → priority/critical
        final WorkItemFilter filterB = new WorkItemFilter();
        filterB.name = "Security-B: Critical Priority Flag";
        filterB.scope = FilterScope.ORG;
        filterB.conditionLanguage = "jexl";
        filterB.conditionExpression = "priority == 'CRITICAL'";
        filterB.actions = WorkItemFilter.serializeActions(List.of(
                FilterAction.applyLabel("priority/critical")));
        filterB.active = true;
        filterB.persist();

        // Filter C: BOTH labels present → executive escalation (cascade)
        // This is the key insight: single filters can't express AND-of-separate-conditions
        // without intermediate labels acting as signals
        final WorkItemFilter filterC = new WorkItemFilter();
        filterC.name = "Security-C: Critical Incident → Executive Escalation";
        filterC.scope = FilterScope.ORG;
        filterC.conditionLanguage = "jexl";
        filterC.conditionExpression = "labels.contains('security/incident') && labels.contains('priority/critical')";
        filterC.actions = WorkItemFilter.serializeActions(List.of(
                FilterAction.applyLabel("security/exec-escalate")));
        filterC.active = true;
        filterC.persist();
    }

    /**
     * Run the multi-label security escalation scenario end to end.
     *
     * @return scenario response with steps and security/exec-escalate queue contents
     */
    @POST
    @Path("/run")
    @Transactional
    public QueueScenarioResponse run() {
        setupFilters();
        final List<QueueScenarioStep> steps = new ArrayList<>();

        LOG.info("[SECURITY] Step 1/3: HIGH security incident — security/incident only (not CRITICAL)");
        final WorkItem highIncident = workItemService.create(new WorkItemCreateRequest(
                "Suspicious login attempts — automated bot detected",
                "Rate limiter flagged 2,400 failed login attempts from a single IP range over 10 minutes.",
                "security",
                "login-anomaly",
                WorkItemPriority.HIGH,
                null, "security-team", null, null,
                "siem-system",
                "{\"source_ip_range\": \"185.220.x.x\", \"attempts\": 2400, \"period_minutes\": 10}",
                null, null, null, null));

        steps.add(new QueueScenarioStep(1,
                "HIGH security anomaly — filter A fires: security/incident; filter B (CRITICAL) does not match; filter C (cascade) cannot fire without priority/critical",
                highIncident.id, inferredPaths(highIncident), manualPaths(highIncident)));

        LOG.info("[SECURITY] Step 2/3: CRITICAL security breach — all 3 labels via cascade");
        final WorkItem criticalBreach = workItemService.create(new WorkItemCreateRequest(
                "Data breach confirmed — customer PII exfiltrated",
                "Forensic analysis confirms unauthorised exfiltration of 340,000 customer records " +
                        "including name, email, and hashed passwords. GDPR 72-hour notification window started.",
                "security",
                "data-breach",
                WorkItemPriority.CRITICAL,
                null, "security-team,legal-team,executive-team", null, null,
                "forensics-system",
                "{\"records_affected\": 340000, \"data_types\": [\"name\", \"email\", \"password_hash\"], " +
                        "\"gdpr_window_hours\": 72, \"incident_id\": \"SEC-BREACH-2026-001\"}",
                null, null, null, null));

        steps.add(new QueueScenarioStep(2,
                "CRITICAL data breach — filter A: security/incident, filter B: priority/critical, filter C cascades (both labels present): security/exec-escalate",
                criticalBreach.id, inferredPaths(criticalBreach), manualPaths(criticalBreach)));

        LOG.info("[SECURITY] Step 3/3: security/exec-escalate queue — CRITICAL incident only");
        final List<UUID> execEscalateQueue = workItemRepo.findByLabelPattern("security/exec-escalate")
                .stream().map(w -> w.id).toList();

        steps.add(new QueueScenarioStep(3,
                "security/exec-escalate queue — CRITICAL breach present; HIGH anomaly absent (did not meet cascade threshold)",
                null,
                List.of("security/exec-escalate contains " + execEscalateQueue.size() + " item(s)"),
                List.of()));

        return new QueueScenarioResponse(
                "security-exec-escalation",
                "Multi-label stacking cascade: security+CRITICAL→exec-escalate; impossible with single-filter logic alone",
                steps, execEscalateQueue);
    }

    private List<String> inferredPaths(final WorkItem wi) {
        return wi.labels.stream()
                .filter(l -> l.persistence == LabelPersistence.INFERRED)
                .map(l -> l.path).toList();
    }

    private List<String> manualPaths(final WorkItem wi) {
        return wi.labels.stream()
                .filter(l -> l.persistence == LabelPersistence.MANUAL)
                .map(l -> l.path).toList();
    }
}
