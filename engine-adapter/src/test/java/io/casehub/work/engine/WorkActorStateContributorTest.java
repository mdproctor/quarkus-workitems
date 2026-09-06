package io.casehub.work.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.platform.api.actor.ActorStateAccumulator;
import io.casehub.work.api.WorkItem;
import io.casehub.work.api.WorkItemQuery;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.api.spi.WorkItemStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkActorStateContributorTest {

  private WorkItemStore store;
  private ActorStateAccumulator accumulator;
  private WorkActorStateContributor contributor;

  @BeforeEach
  void setUp() {
    store = mock(WorkItemStore.class);
    accumulator = mock(ActorStateAccumulator.class);
    contributor = new WorkActorStateContributor(store);
  }

  @Test
  void sourceName_returnsWork() {
    assertThat(contributor.sourceName()).isEqualTo("work");
  }

  @Test
  void contribute_activeItems_callsAccumulatorForEach() {
    var id1 = UUID.randomUUID();
    var id2 = UUID.randomUUID();
    var caseId = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

    var item1 = workItem(id1, "Review SAR", WorkItemStatus.ASSIGNED,
        "case:" + caseId + "/pi:step-1");
    var item2 = workItem(id2, "Approve transfer", WorkItemStatus.IN_PROGRESS,
        "case:" + caseId + "/gate:42");

    when(store.scan(any(WorkItemQuery.class))).thenReturn(List.of(item1, item2));

    contributor.contribute("agent-x", accumulator);

    var idCaptor = ArgumentCaptor.forClass(UUID.class);
    var titleCaptor = ArgumentCaptor.forClass(String.class);
    var statusCaptor = ArgumentCaptor.forClass(String.class);
    var categoryCaptor = ArgumentCaptor.forClass(String.class);
    var caseIdCaptor = ArgumentCaptor.forClass(UUID.class);

    verify(accumulator, org.mockito.Mockito.times(2)).workItem(
        idCaptor.capture(), titleCaptor.capture(), statusCaptor.capture(),
        categoryCaptor.capture(), caseIdCaptor.capture());

    assertThat(idCaptor.getAllValues()).containsExactly(id1, id2);
    assertThat(titleCaptor.getAllValues()).containsExactly("Review SAR", "Approve transfer");
    assertThat(statusCaptor.getAllValues()).containsExactly("ASSIGNED", "IN_PROGRESS");
    assertThat(caseIdCaptor.getAllValues()).containsExactly(caseId, caseId);
  }

  @Test
  void contribute_planItemCallerRef_extractsCaseId() {
    var id = UUID.randomUUID();
    var caseId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    var item = workItem(id, "Task", WorkItemStatus.ASSIGNED,
        "case:" + caseId + "/pi:plan-item-1");

    when(store.scan(any())).thenReturn(List.of(item));
    contributor.contribute("agent-x", accumulator);

    verify(accumulator).workItem(id, "Task", "ASSIGNED", null, caseId);
  }

  @Test
  void contribute_gateCallerRef_extractsCaseId() {
    var id = UUID.randomUUID();
    var caseId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    var item = workItem(id, "Gate", WorkItemStatus.IN_PROGRESS,
        "case:" + caseId + "/gate:99");

    when(store.scan(any())).thenReturn(List.of(item));
    contributor.contribute("agent-x", accumulator);

    verify(accumulator).workItem(id, "Gate", "IN_PROGRESS", null, caseId);
  }

  @Test
  void contribute_nonEngineCallerRef_caseIdIsNull() {
    var id = UUID.randomUUID();
    var item = workItem(id, "Qhorus task", WorkItemStatus.ASSIGNED,
        "qhorus:channel-1/msg-1/corr-1");

    when(store.scan(any())).thenReturn(List.of(item));
    contributor.contribute("agent-x", accumulator);

    verify(accumulator).workItem(id, "Qhorus task", "ASSIGNED", null, null);
  }

  @Test
  void contribute_nullCallerRef_caseIdIsNull() {
    var id = UUID.randomUUID();
    var item = workItem(id, "Manual task", WorkItemStatus.SUSPENDED, null);

    when(store.scan(any())).thenReturn(List.of(item));
    contributor.contribute("agent-x", accumulator);

    verify(accumulator).workItem(id, "Manual task", "SUSPENDED", null, null);
  }

  @Test
  void contribute_noActiveItems_noAccumulatorCalls() {
    when(store.scan(any())).thenReturn(List.of());
    contributor.contribute("agent-x", accumulator);

    verify(accumulator, never()).workItem(any(), any(), any(), any(), any());
  }

  @Test
  void contribute_queryUsesCorrectStatusFilter() {
    when(store.scan(any())).thenReturn(List.of());

    contributor.contribute("agent-x", accumulator);

    var captor = ArgumentCaptor.forClass(WorkItemQuery.class);
    verify(store).scan(captor.capture());
    var query = captor.getValue();

    assertThat(query.assigneeId()).isEqualTo("agent-x");
    assertThat(query.statusIn()).containsExactlyInAnyOrder(
        WorkItemStatus.ASSIGNED,
        WorkItemStatus.IN_PROGRESS,
        WorkItemStatus.SUSPENDED);
  }

  @Test
  void contribute_nullTitle_passedThrough() {
    var id = UUID.randomUUID();
    var item = workItem(id, null, WorkItemStatus.ASSIGNED, null);

    when(store.scan(any())).thenReturn(List.of(item));
    contributor.contribute("agent-x", accumulator);

    verify(accumulator).workItem(id, null, "ASSIGNED", null, null);
  }

  private WorkItem workItem(UUID id, String title, WorkItemStatus status, String callerRef) {
    return WorkItem.builder()
        .id(id)
        .title(title)
        .status(status)
        .callerRef(callerRef)
        .tenancyId("tenant-abc")
        .build();
  }
}
