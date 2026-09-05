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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.internal.model.TargetType;
import io.casehub.engine.common.spi.JudgmentRequest;
import io.casehub.engine.common.spi.JudgmentScheduleRequest;
import io.casehub.engine.common.spi.JudgmentScheduler;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.work.api.Outcome;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.spi.WorkItemCreator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@ApplicationScoped
@SuppressWarnings("removal")
public class JudgmentWorkItemScheduler implements JudgmentScheduler {

  private static final Logger LOG = Logger.getLogger(JudgmentWorkItemScheduler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject BlackboardRegistry registry;
  @Inject WorkItemCreator workItemCreator;
  @Inject PlanItemStore planItemStore;

  @Override
  @Transactional
  public void schedule(JudgmentScheduleRequest request) {
    CasePlanModel plan = registry.get(request.caseId()).orElse(null);
    if (plan == null) {
      LOG.warnf(
          "No CasePlanModel for caseId=%s — judgment not dispatched",
          request.caseId());
      return;
    }

    PlanItem item = plan.getPlanItemByBindingName(request.bindingName()).orElse(null);
    if (item == null) {
      LOG.warnf(
          "PlanItem for binding '%s' not found in case %s",
          request.bindingName(), request.caseId());
      return;
    }

    if (item.getStatus() != TaskStatus.DISPATCHING) {
      LOG.warnf(
          "PlanItem for binding '%s' case %s is not DISPATCHING (status=%s) — skipping",
          request.bindingName(), request.caseId(), item.getStatus());
      return;
    }

    String callerRef = PlanItemRef.encode(request.caseId(), item.getPlanItemId());
    JudgmentTarget target = request.target();

    String payload = null;
    if (request.inputData() != null && !request.inputData().isEmpty()) {
      try {
        payload = MAPPER.writeValueAsString(request.inputData());
      } catch (Exception e) {
        LOG.warnf(e, "Failed to serialize inputData for judgment binding '%s' caseId=%s",
            request.bindingName(), request.caseId());
      }
    }

    WorkItemCreateRequest.Builder builder = WorkItemCreateRequest.builder()
        .title(request.resolvedTitle() != null ? request.resolvedTitle() : target.title())
        .createdBy("casehub-engine")
        .callerRef(callerRef)
        .scope(request.resolvedScope() != null ? request.resolvedScope() : target.scope())
        .payload(payload)
        .candidateGroups(toCsv(request.resolvedCandidateGroups()))
        .candidateUsers(toCsv(request.resolvedCandidateUsers()))
        .expiresAt(earliestOf(request.expiresAtDeadline(), request.caseBudgetDeadline()))
        .payloadTypeName(request.payloadTypeName())
        .resolutionTypeName(request.resolutionTypeName())
        .candidateScores(serializeScores(request.candidateScores()))
        .routingExperiences(serializeExperiences(request.experiences()))
        .tenancyId(request.tenancyId())
        .originRef(resolveOriginRef(request, item, plan));

    if (target.outcomes() != null && !target.outcomes().isEmpty()) {
      builder.permittedOutcomes(toOutcomeList(target.outcomes()));
    }

    try {
      workItemCreator.create(builder.build());
    } catch (Exception e) {
      LOG.warnf(
          "Failed to create WorkItem for judgment binding '%s' case %s — reverting to PENDING: %s",
          request.bindingName(), request.caseId(), e.getMessage());
      item.revertDispatching();
      return;
    }

    planItemStore.save(
        PlanItemSaveRequest.primitive(
            request.caseId(),
            item.getPlanItemId(),
            item.getBindingName(),
            TaskStatus.DELEGATED,
            item.getCreatedAt(),
            TargetType.JUDGMENT,
            extractOutputMappingExpression(target),
            request.tenancyId(),
            null,
            null,
            null),
        request.tenancyId());
    item.markDelegated();
    LOG.infof("WorkItem created (judgment) for binding callerRef=%s", callerRef);
  }

  @Override
  public void schedule(JudgmentRequest request) {
    JudgmentScheduler.super.schedule(request);
  }

    private String resolveOriginRef(JudgmentScheduleRequest request, PlanItem item,
                                    CasePlanModel plan) {
        if (request.originRef() != null) {
            return request.originRef();
        }
        if (item.isCompensation() && item.getCompensatesItemId() != null) {
            PlanItem originalItem = plan.getPlanItem(item.getCompensatesItemId()).orElse(null);
            if (originalItem != null) {
                String originalCallerRef = PlanItemRef.encode(request.caseId(), originalItem.getPlanItemId());
                return workItemCreator.findByCallerRef(originalCallerRef)
                                      .map(ref -> ref.originRef())
                                      .orElse(null);
            }
        }
        return null;
    }


    private static String extractOutputMappingExpression(JudgmentTarget target) {
    if (target == null || target.outputMapping() == null) return null;
    if (target.outputMapping() instanceof JQExpressionEvaluator jq) return jq.expression();
    return null;
  }

  private static Instant earliestOf(Instant a, Instant b) {
    if (a == null) return b;
    if (b == null) return a;
    return a.isBefore(b) ? a : b;
  }

  private static String toCsv(Set<String> values) {
    if (values == null || values.isEmpty()) return null;
    return String.join(",", values);
  }

  private static List<Outcome> toOutcomeList(Set<String> outcomeNames) {
    return outcomeNames.stream().map(n -> new Outcome(n, null, null)).toList();
  }

  private String serializeScores(java.util.Map<String, Double> scores) {
    if (scores == null || scores.isEmpty()) return null;
    try {
      return MAPPER.writeValueAsString(scores);
    } catch (Exception e) {
      LOG.warnf(e, "Failed to serialize candidateScores");
      return null;
    }
  }

  private String serializeExperiences(
      java.util.List<io.casehub.api.spi.routing.RetrievedExperience> experiences) {
    if (experiences == null || experiences.isEmpty()) return null;
    try {
      return MAPPER.writeValueAsString(experiences);
    } catch (Exception e) {
      LOG.warnf(e, "Failed to serialize routingExperiences");
      return null;
    }
  }
}
