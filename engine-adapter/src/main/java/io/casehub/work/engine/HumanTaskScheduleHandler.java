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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.internal.model.TargetType;
import io.casehub.engine.common.spi.HumanTaskScheduleRequest;
import io.casehub.engine.common.spi.HumanTaskScheduler;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Schedules human task work items when a {@link HumanTaskTarget} binding is selected.
 *
 * <p>Implements {@link HumanTaskScheduler} — discovered by the engine runtime via CDI {@code
 * Instance<HumanTaskScheduler>}. Looks up the {@link PlanItem} in the {@link BlackboardRegistry} by
 * binding name, creates a WorkItem via {@link WorkItemCreator} (inline or template mode), persists
 * DELEGATED status to {@link PlanItemStore}, then marks the in-memory PlanItem DELEGATED.
 *
 * <p>All three steps — WorkItem creation, {@code planItemStore.save(...DELEGATED...)}, and {@code
 * item.markDelegated()} — execute in a single {@code @Transactional} boundary. If WorkItem creation
 * fails, the transaction rolls back and {@code markDelegated()} is never called, leaving the
 * PlanItem PENDING. Refs engine#273.
 *
 * <p>The {@code callerRef} encodes {@code case:{caseId}/pi:{planItemId}} so that {@link
 * WorkItemLifecycleAdapter} can route the completion event back to the correct case and plan item.
 * Refs engine#245, work#298.
 */
@ApplicationScoped
@SuppressWarnings("removal")
public class HumanTaskScheduleHandler implements HumanTaskScheduler {

  private static final Logger LOG = Logger.getLogger(HumanTaskScheduleHandler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject BlackboardRegistry registry;
  @Inject WorkItemCreator workItemCreator;
  @Inject PlanItemStore planItemStore;

  @Override
  @Transactional
  public void schedule(HumanTaskScheduleRequest request) {
    CasePlanModel plan = registry.get(request.caseId()).orElse(null);
    if (plan == null) {
      LOG.warnf(
          "No CasePlanModel for caseId=%s — case may not use blackboard or has completed",
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

    if (request.target().isTemplateMode()) {
      handleTemplateMode(item, request);
    } else {
      handleInlineMode(item, request);
    }
  }

  private void handleTemplateMode(PlanItem item, HumanTaskScheduleRequest request) {
    final HumanTaskTarget target = request.target();

    final UUID templateId;
    try {
      templateId = UUID.fromString(target.templateRef());
    } catch (IllegalArgumentException e) {
      LOG.warnf(
          "templateRef '%s' is not a valid UUID for binding '%s' case %s — reverting to PENDING",
          target.templateRef(), request.bindingName(), request.caseId());
      item.revertDispatching();
      return;
    }

    final String callerRef = PlanItemRef.encode(request.caseId(), item.getPlanItemId());
    final String payload =
        (request.inputData() != null && !request.inputData().isEmpty())
            ? serializePayload(request.inputData())
            : null;

    final WorkItemCreateRequest.Builder requestBuilder =
        WorkItemCreateRequest.builder()
            .templateId(templateId)
            .title(
                request.resolvedTitle() != null ? request.resolvedTitle() : target.title())
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
            .claimDeadlineBusinessHours(target.claimDeadlineHours());
    if (target.outcomes() != null && !target.outcomes().isEmpty()) {
      requestBuilder.permittedOutcomes(toOutcomeList(target.outcomes()));
    }
    try {
      workItemCreator.create(requestBuilder.build());
    } catch (final Exception e) {
      LOG.warnf(
          "Failed to create WorkItem from template '%s' binding '%s' case %s — reverting to PENDING: %s",
          target.templateRef(), request.bindingName(), request.caseId(), e.getMessage());
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
            TargetType.HUMAN_TASK,
            extractOutputMappingExpression(request.target()),
            request.tenancyId(),
            null,
            null,
            null),
        request.tenancyId());
    item.markDelegated();
    LOG.infof("WorkItem created (template) for binding callerRef=%s", callerRef);
  }

  private void handleInlineMode(PlanItem item, HumanTaskScheduleRequest request) {
    String callerRef = PlanItemRef.encode(request.caseId(), item.getPlanItemId());
    try {
      createInline(
          request.target(),
          request.inputData(),
          request.resolvedCandidateGroups(),
          request.resolvedCandidateUsers(),
          callerRef,
          request.expiresAtDeadline(),
          request.caseBudgetDeadline(),
          request.payloadTypeName(),
          request.resolutionTypeName(),
          request.candidateScores(),
          request.experiences(),
          request.resolvedTitle(),
          request.resolvedScope());
    } catch (Exception e) {
      LOG.warnf(
          "Failed to create inline WorkItem for binding '%s' case %s — reverting to PENDING: %s",
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
            TargetType.HUMAN_TASK,
            extractOutputMappingExpression(request.target()),
            request.tenancyId(),
            null,
            null,
            null),
        request.tenancyId());
    item.markDelegated();
  }

  private void createInline(
      HumanTaskTarget target,
      Map<String, Object> inputData,
      Set<String> resolvedGroups,
      Set<String> resolvedUsers,
      String callerRef,
      Instant expiresAtDeadline,
      Instant caseBudgetDeadline,
      String payloadTypeName,
      String resolutionTypeName,
      Map<String, Double> candidateScores,
      List<RetrievedExperience> experiences,
      String resolvedTitle,
      String resolvedScope) {
    String payload = serializePayload(inputData);
    Instant taskDeadline =
        target.expiresIn() != null ? Instant.now().plus(target.expiresIn()) : null;
    Instant effectiveDeadline =
        earliestOf(earliestOf(taskDeadline, expiresAtDeadline), caseBudgetDeadline);

    WorkItemCreateRequest.Builder requestBuilder =
        WorkItemCreateRequest.builder()
            .title(resolvedTitle != null ? resolvedTitle : target.title())
            .candidateGroups(toCsv(resolvedGroups))
            .candidateUsers(toCsv(resolvedUsers))
            .createdBy("casehub-engine")
            .payload(payload)
            .expiresAt(effectiveDeadline)
            .claimDeadlineBusinessHours(target.claimDeadlineHours())
            .callerRef(callerRef)
            .scope(resolvedScope != null ? resolvedScope : target.scope())
            .payloadTypeName(payloadTypeName)
            .resolutionTypeName(resolutionTypeName)
            .candidateScores(serializeScores(candidateScores))
            .routingExperiences(serializeExperiences(experiences));
    if (target.outcomes() != null && !target.outcomes().isEmpty()) {
      requestBuilder.permittedOutcomes(toOutcomeList(target.outcomes()));
    }
    WorkItemCreateRequest workItemRequest = requestBuilder.build();

    workItemCreator.create(workItemRequest);
    LOG.infof(
        "WorkItem created (inline) for binding callerRef=%s title='%s' expiresAt=%s",
        callerRef, target.title(), effectiveDeadline);
  }

  private static Instant earliestOf(Instant a, Instant b) {
    if (a == null) return b;
    if (b == null) return a;
    return a.isBefore(b) ? a : b;
  }

  private String serializePayload(Map<String, Object> inputData) {
    if (inputData == null || inputData.isEmpty()) return null;
    try {
      return MAPPER.writeValueAsString(inputData);
    } catch (JsonProcessingException e) {
      LOG.warnf(e, "Failed to serialize inputData to JSON payload — using null");
      return null;
    }
  }

  private String serializeScores(Map<String, Double> scores) {
    if (scores == null || scores.isEmpty()) return null;
    try {
      return MAPPER.writeValueAsString(scores);
    } catch (JsonProcessingException e) {
      LOG.warnf(e, "Failed to serialize candidateScores — using null");
      return null;
    }
  }

  private String serializeExperiences(List<RetrievedExperience> experiences) {
    if (experiences == null || experiences.isEmpty()) return null;
    try {
      return MAPPER.writeValueAsString(experiences);
    } catch (JsonProcessingException e) {
      LOG.warnf(e, "Failed to serialize routing experiences — using null");
      return null;
    }
  }

  private static List<Outcome> toOutcomeList(Set<String> outcomeNames) {
    return outcomeNames.stream().map(name -> new Outcome(name, null, null)).toList();
  }

  private static String toCsv(Set<String> values) {
    if (values == null || values.isEmpty()) return null;
    return String.join(",", values);
  }

  private static String extractOutputMappingExpression(HumanTaskTarget target) {
    if (target == null || target.outputMapping() == null) return null;
    if (target.outputMapping() instanceof JQExpressionEvaluator jq) return jq.expression();
    return null;
  }
}
