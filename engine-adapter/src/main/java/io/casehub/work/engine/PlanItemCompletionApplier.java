/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.work.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ConflictResolver;
import io.casehub.api.model.ExtensionTarget;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.model.SignalTarget;
import io.casehub.api.model.SubCaseTarget;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.event.PlanItemObsoleteEvent;
import io.casehub.engine.common.spi.event.PlanItemStateChangedEvent;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.work.api.WorkItemRef;
import io.casehub.work.api.WorkItemStatus;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class PlanItemCompletionApplier {

    private static final Logger                             LOG      = Logger.getLogger(PlanItemCompletionApplier.class);
    private static final ObjectMapper                       MAPPER   = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Inject
    BlackboardRegistry                                       registry;
    @Inject
    CaseDefinitionRegistry                                   caseDefinitionRegistry;
    @Inject
    CrossTenantCaseInstanceRepository                        caseInstanceRepository;
    @Inject
    EventBus                                                 eventBus;
    @Inject
    JQEvaluator                                              jqEvaluator;
    @Inject
    io.casehub.engine.common.internal.context.BridgeResolver bridgeResolver;
    @Inject
    Event<PlanItemStateChangedEvent>                         planItemStateChangedEvents;
    @Inject
    Event<PlanItemObsoleteEvent>                             planItemObsoleteEvents;

    /**
     * Applies the terminal WorkItemStatus to the PlanItem, runs outputMapping if configured, loads
     * the CaseInstance, and publishes CONTEXT_CHANGED.
     *
     * <p>If the PlanItem is already terminal (idempotency), logs DEBUG and returns without throwing.
     *
     * @param caseId     the case containing the PlanItem
     * @param planItemId the PlanItem to transition
     * @param status     the terminal WorkItemStatus to apply
     * @param ref        the source WorkItemRef (for outputMapping resolution JSON); may be null
     * @param workLedgerEntryId the work-ledger entry ID for this terminal transition; may be null
     */
    @Transactional
    public void apply(UUID caseId, String planItemId, WorkItemStatus status, WorkItemRef ref,
                      UUID workLedgerEntryId) {
        LOG.debugf("PlanItem %s completion: workLedgerEntryId=%s", planItemId, workLedgerEntryId);
        PlanItem item = registry.get(caseId).flatMap(plan -> plan.getPlanItem(planItemId)).orElse(null);

        if (item == null) {
            LOG.warnf("PlanItem %s not found in case %s — completion not applied", planItemId, caseId);
            return;
        }

        CaseInstance instance = caseInstanceRepository.findByUuid(caseId);
        if (instance == null) {
            LOG.warnf("CaseInstance not found for caseId=%s — CONTEXT_CHANGED not fired", caseId);
            return;
        }

        if (status != WorkItemStatus.ESCALATED
            && ref != null && ref.resolutionTypeName() != null && ref.resolution() != null) {
            try {
                var bridge = bridgeResolver.resolveByTypeNameStrict(ref.resolutionTypeName());
                bridge.deserialise(MAPPER.readTree(ref.resolution()));
            } catch (Exception e) {
                LOG.warnf(e,
                          "Resolution validation failed for PlanItem %s caseId=%s — "
                          + "resolution does not match resolutionType %s",
                          planItemId, caseId, ref.resolutionTypeName());
                writeValidationFailedSignal(instance, item, ref, e);
                return;
            }
        }

        TaskStatus previousStatus = item.getStatus();
        if (!applyStatus(item, status)) {
            return;
        }

        if (status == WorkItemStatus.ESCALATED) {
            writeEscalationSignal(instance, item, ref);
        }

        applyOutputMapping(item, ref, instance);

        final String bindingName = item.getBindingName();
        if (status == WorkItemStatus.COMPLETED) {
            planItemStateChangedEvents.fireAsync(
                    new PlanItemStateChangedEvent(caseId, planItemId, bindingName,
                                                  previousStatus, TaskStatus.COMPLETED, instance.tenancyId));
        }
        if (status == WorkItemStatus.REJECTED) {
            planItemStateChangedEvents.fireAsync(
                    new PlanItemStateChangedEvent(caseId, planItemId, bindingName,
                                                  previousStatus, TaskStatus.REJECTED, instance.tenancyId));
        }
        if (status == WorkItemStatus.FAULTED || status == WorkItemStatus.EXPIRED
            || status == WorkItemStatus.ESCALATED) {
            planItemStateChangedEvents.fireAsync(
                    new PlanItemStateChangedEvent(caseId, planItemId, bindingName,
                                                  previousStatus, TaskStatus.FAULTED, instance.tenancyId));
        }
        if (status == WorkItemStatus.OBSOLETE) {
            planItemObsoleteEvents.fireAsync(
                    new PlanItemObsoleteEvent(caseId, planItemId, bindingName, instance.tenancyId));
        }

        eventBus.publish(
                EventBusAddresses.CONTEXT_CHANGED,
                new CaseContextChangedEvent(
                        instance, instance.getCaseContext().snapshot(), ContextLayer.WORKING));
    }

    private boolean applyStatus(PlanItem item, WorkItemStatus status) {
        try {
            switch (status) {
                case COMPLETED -> item.markCompleted();
                case REJECTED -> item.markRejected();
                case FAULTED -> item.markFaulted();
                case EXPIRED -> item.markFaulted();
                case ESCALATED -> item.markFaulted();
                case OBSOLETE -> item.markObsolete();
                case CANCELLED -> item.markCancelled();
                default -> {
                    LOG.warnf(
                            "Unhandled WorkItemStatus %s for PlanItem %s — no transition applied",
                            status, item.getPlanItemId());
                    return false;
                }
            }
            return true;
        } catch (IllegalStateException e) {
            LOG.debugf(
                    "PlanItem %s already terminal (status=%s) — skipping for WorkItemStatus %s",
                    item.getPlanItemId(), item.getStatus(), status);
            return false;
        }
    }

    private void applyOutputMapping(PlanItem item, WorkItemRef ref, CaseInstance instance) {
        if (instance.getCaseContext() == null) {return;}
        if (item.getTarget() == null) {return;}
        io.casehub.platform.api.expression.ExpressionEvaluator outputMapping = null;
        if (item.getTarget() instanceof JudgmentTarget jt) {
            outputMapping = jt.outputMapping();
        }
        if (outputMapping == null) {return;}
        if (ref == null) {return;}

        if (!(outputMapping instanceof JQExpressionEvaluator jq)) {
            LOG.warnf(
                    "Unsupported outputMapping evaluator type '%s' for PlanItem %s — skipping",
                    outputMapping.getClass().getName(), item.getPlanItemId());
            return;
        }

        try {
            JsonNode resolutionNode = ref.resolution() != null
                    ? MAPPER.readTree(ref.resolution())
                    : MAPPER.createObjectNode();
            ValidationResult vr = jqEvaluator.eval(jq.expression(), resolutionNode);
            if (!vr.ok() || vr.output() == null || vr.output().isEmpty()) {
                LOG.warnf(
                        "outputMapping jq expression returned no result for PlanItem %s: %s",
                        item.getPlanItemId(), vr.error());
                return;
            }
            List<JsonNode>      output   = vr.output();
            Map<String, Object> updates  = MAPPER.convertValue(output.get(0), MAP_TYPE);
            String              strategy = resolveStrategy(item.getBindingName(), instance);
            for (Map.Entry<String, Object> entry : updates.entrySet()) {
                Object existing = instance.getCaseContext().get(entry.getKey());
                Object resolved =
                        ConflictResolver.resolve(strategy, entry.getKey(), existing, entry.getValue());
                instance.getCaseContext().set(entry.getKey(), resolved);
            }
        } catch (Exception e) {
            LOG.warnf(
                    e,
                    "outputMapping failed for PlanItem %s — CONTEXT_CHANGED fires without output update",
                    item.getPlanItemId());
        }
    }

    private String resolveStrategy(String bindingName, CaseInstance instance) {
        if (bindingName == null || instance.getCaseMetaModel() == null) {return null;}
        CaseDefinition def = caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
        if (def == null || def.getBindings() == null) {return null;}
        return def.getBindings().stream()
                  .filter(b -> b.getName().equals(bindingName))
                  .map(Binding::getConflictResolverStrategy)
                  .findFirst()
                  .orElse(null);
    }


    private void writeEscalationSignal(CaseInstance instance, PlanItem item, WorkItemRef ref) {
        final List<String> lastGroups =
                ref.candidateGroups() != null
                ? List.of(ref.candidateGroups().split("\\s*,\\s*"))
                : List.of();
        instance
                .getCaseContext()
                .set(
                        "workItemEscalated",
                        Map.of(
                                "workItemId", ref.id().toString(),
                                "lastCandidateGroups", lastGroups,
                                "bindingName", item.getBindingName()));
    }

    private void writeValidationFailedSignal(
            CaseInstance instance, PlanItem item, WorkItemRef ref, Exception cause) {
        instance
                .getCaseContext()
                .set(
                        "workItemValidationFailed",
                        Map.of(
                                "workItemId", ref.id().toString(),
                                "bindingName", item.getBindingName(),
                                "resolutionTypeName", ref.resolutionTypeName(),
                                "error",
                                cause.getMessage() != null
                                ? cause.getMessage()
                                : cause.getClass().getName()));
        eventBus.publish(
                EventBusAddresses.CONTEXT_CHANGED,
                new CaseContextChangedEvent(
                        instance, instance.getCaseContext().snapshot(), ContextLayer.WORKING));
    }
}
