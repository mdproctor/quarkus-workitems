package io.quarkiverse.work.examples.queues.security;

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

import io.quarkiverse.work.examples.queues.QueueScenarioResponse;
import io.quarkiverse.work.examples.queues.QueueScenarioStep;
import io.quarkiverse.work.examples.queues.lifecycle.QueueEventLog;
import io.quarkiverse.work.queues.model.FilterAction;
import io.quarkiverse.work.queues.model.FilterScope;
import io.quarkiverse.work.queues.model.QueueView;
import io.quarkiverse.work.queues.model.WorkItemFilter;
import io.quarkiverse.work.runtime.model.LabelPersistence;
import io.quarkiverse.work.runtime.model.WorkItem;
import io.quarkiverse.work.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.work.runtime.model.WorkItemPriority;
import io.quarkiverse.work.runtime.repository.WorkItemQuery;
import io.quarkiverse.work.runtime.repository.WorkItemStore;
import io.quarkiverse.work.runtime.service.WorkItemService;

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
 * Queue events per step:
 * <ol>
 * <li>HIGH security incident → ADDED to Security Incidents Queue only</li>
 * <li>CRITICAL breach → ADDED to Security Incidents Queue + ADDED to Priority Critical Queue +
 * ADDED to Security Exec Escalation Queue (cascade fires after both A and B labels present)</li>
 * </ol>
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
    WorkItemStore workItemStore;

    @Inject
    QueueEventLog eventLog;

    private void setupFilters() {
        if (WorkItemFilter.count("name", "Security-A: Incident Detection") > 0)
            return;

        final WorkItemFilter filterA = new WorkItemFilter();
        filterA.name = "Security-A: Incident Detection";
        filterA.scope = FilterScope.ORG;
        filterA.conditionLanguage = "jexl";
        filterA.conditionExpression = "category == 'security'";
        filterA.actions = WorkItemFilter.serializeActions(List.of(
                FilterAction.applyLabel("security/incident")));
        filterA.active = true;
        filterA.persist();

        final WorkItemFilter filterB = new WorkItemFilter();
        filterB.name = "Security-B: Critical Priority Flag";
        filterB.scope = FilterScope.ORG;
        filterB.conditionLanguage = "jexl";
        filterB.conditionExpression = "priority == 'CRITICAL'";
        filterB.actions = WorkItemFilter.serializeActions(List.of(
                FilterAction.applyLabel("priority/critical")));
        filterB.active = true;
        filterB.persist();

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

    private void setupQueueViews() {
        if (QueueView.count("name", "Security Incidents Queue") > 0)
            return;

        final QueueView incidents = new QueueView();
        incidents.name = "Security Incidents Queue";
        incidents.labelPattern = "security/incident";
        incidents.scope = FilterScope.ORG;
        incidents.persist();

        final QueueView critical = new QueueView();
        critical.name = "Priority Critical Queue";
        critical.labelPattern = "priority/critical";
        critical.scope = FilterScope.ORG;
        critical.persist();

        final QueueView exec = new QueueView();
        exec.name = "Security Exec Escalation Queue";
        exec.labelPattern = "security/exec-escalate";
        exec.scope = FilterScope.ORG;
        exec.persist();
    }

    /**
     * Run the multi-label security escalation scenario end to end.
     *
     * @return scenario response with steps, queue events per step, and exec-escalate queue contents
     */
    @POST
    @Path("/run")
    @Transactional
    public QueueScenarioResponse run() {
        setupFilters();
        setupQueueViews();
        eventLog.clear();
        final List<QueueScenarioStep> steps = new ArrayList<>();

        LOG.info("[SECURITY] Step 1/3: HIGH security incident — security/incident only (not CRITICAL)");
        final WorkItem highIncident = workItemService.create(new WorkItemCreateRequest(
                "Suspicious login attempts — automated bot detected",
                "Rate limiter flagged 2,400 failed login attempts from a single IP range over 10 minutes.",
                "security", "login-anomaly", WorkItemPriority.HIGH,
                null, "security-team", null, null, "siem-system",
                "{\"source_ip_range\": \"185.220.x.x\", \"attempts\": 2400, \"period_minutes\": 10}",
                null, null, null, null, null, null, null, null));
        steps.add(new QueueScenarioStep(1,
                "HIGH security anomaly — filter A fires: security/incident; filter B (CRITICAL) does not match; filter C (cascade) cannot fire without priority/critical",
                highIncident.id, inferredPaths(highIncident), manualPaths(highIncident),
                formatEvents(eventLog.drain())));

        LOG.info("[SECURITY] Step 2/3: CRITICAL security breach — all 3 labels via cascade");
        final WorkItem criticalBreach = workItemService.create(new WorkItemCreateRequest(
                "Data breach confirmed — customer PII exfiltrated",
                "Forensic analysis confirms unauthorised exfiltration of 340,000 customer records.",
                "security", "data-breach", WorkItemPriority.CRITICAL,
                null, "security-team,legal-team,executive-team", null, null, "forensics-system",
                "{\"records_affected\": 340000, \"data_types\": [\"name\", \"email\", \"password_hash\"], " +
                        "\"gdpr_window_hours\": 72, \"incident_id\": \"SEC-BREACH-2026-001\"}",
                null, null, null, null, null, null, null, null));
        steps.add(new QueueScenarioStep(2,
                "CRITICAL data breach — filter A: security/incident, filter B: priority/critical, filter C cascades: security/exec-escalate — 3 queue ADDED events",
                criticalBreach.id, inferredPaths(criticalBreach), manualPaths(criticalBreach),
                formatEvents(eventLog.drain())));

        LOG.info("[SECURITY] Step 3/3: security/exec-escalate queue — CRITICAL incident only");
        final List<UUID> execEscalateQueue = workItemStore
                .scan(WorkItemQuery.byLabelPattern("security/exec-escalate"))
                .stream().map(w -> w.id).toList();
        steps.add(new QueueScenarioStep(3,
                "security/exec-escalate queue — CRITICAL breach present; HIGH anomaly absent (did not meet cascade threshold)",
                null,
                List.of("security/exec-escalate contains " + execEscalateQueue.size() + " item(s)"),
                List.of(), List.of()));

        return new QueueScenarioResponse(
                "security-exec-escalation",
                "Multi-label stacking cascade: security+CRITICAL→exec-escalate; impossible with single-filter logic alone",
                steps, execEscalateQueue);
    }

    private List<String> inferredPaths(final WorkItem wi) {
        return wi.labels.stream().filter(l -> l.persistence == LabelPersistence.INFERRED)
                .map(l -> l.path).toList();
    }

    private List<String> manualPaths(final WorkItem wi) {
        return wi.labels.stream().filter(l -> l.persistence == LabelPersistence.MANUAL)
                .map(l -> l.path).toList();
    }

    private List<String> formatEvents(final List<QueueEventLog.Entry> entries) {
        return entries.stream()
                .map(e -> e.eventType().name() + " to " + e.queueName())
                .toList();
    }
}
