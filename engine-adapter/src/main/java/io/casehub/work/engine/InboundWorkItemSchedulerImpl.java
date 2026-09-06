package io.casehub.work.engine;

import io.casehub.engine.common.spi.InboundWorkItemRequest;
import io.casehub.engine.common.spi.InboundWorkItemScheduler;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.WorkItemPriority;
import io.casehub.work.api.spi.TenantContextExecutor;
import io.casehub.work.api.spi.WorkItemCreator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class InboundWorkItemSchedulerImpl implements InboundWorkItemScheduler {

  @Inject WorkItemCreator workItemCreator;
  @Inject TenantContextExecutor tenantContextExecutor;

  InboundWorkItemSchedulerImpl() {}

  InboundWorkItemSchedulerImpl(
      final WorkItemCreator workItemCreator,
      final TenantContextExecutor tenantContextExecutor) {
    this.workItemCreator = workItemCreator;
    this.tenantContextExecutor = tenantContextExecutor;
  }

  @Override
  public void schedule(final InboundWorkItemRequest request) {
    final WorkItemCreateRequest createRequest = WorkItemCreateRequest.builder()
        .title(request.title())
        .description(request.description())
        .candidateGroups(request.candidateGroups())
        .candidateUsers(request.candidateUsers())
        .callerRef(request.callerRef())
        .scope(request.scope())
        .payload(request.payload())
        .tenancyId(request.tenancyId())
        .createdBy(request.createdBy())
        .priority(request.priority() != null
            ? WorkItemPriority.valueOf(request.priority()) : null)
        .types(request.types())
        .expiresAt(request.expiresAt())
        .build();

    tenantContextExecutor.runInTenantContext(
        request.tenancyId(), () -> workItemCreator.create(createRequest));
  }
}
