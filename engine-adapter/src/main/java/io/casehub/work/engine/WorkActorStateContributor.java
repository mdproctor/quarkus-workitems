package io.casehub.work.engine;

import io.casehub.platform.api.actor.ActorStateAccumulator;
import io.casehub.platform.api.actor.ActorStateContributor;
import io.casehub.work.api.WorkItem;
import io.casehub.work.api.WorkItemQuery;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.api.spi.WorkItemStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class WorkActorStateContributor implements ActorStateContributor {

  @Inject WorkItemStore workItemStore;

  WorkActorStateContributor() {}

  WorkActorStateContributor(final WorkItemStore workItemStore) {
    this.workItemStore = workItemStore;
  }

  @Override
  public String sourceName() {
    return "work";
  }

  @Override
  public void contribute(final String actorId, final ActorStateAccumulator acc) {
    final List<WorkItem> items = workItemStore.scan(
        WorkItemQuery.builder()
            .assigneeId(actorId)
            .statusIn(List.of(
                WorkItemStatus.ASSIGNED,
                WorkItemStatus.IN_PROGRESS,
                WorkItemStatus.SUSPENDED))
            .build());

    for (final WorkItem wi : items) {
      final CallerRef ref = CallerRef.parse(wi.callerRef());
      final UUID caseId = ref != null ? ref.caseId() : null;
      acc.workItem(
          wi.id(),
          wi.title(),
          wi.status() != null ? wi.status().name() : null,
          null,
          caseId);
    }
  }
}
