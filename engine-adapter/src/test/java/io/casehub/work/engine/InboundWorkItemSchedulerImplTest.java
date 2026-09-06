package io.casehub.work.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.casehub.engine.common.spi.InboundWorkItemRequest;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.WorkItemPriority;
import io.casehub.work.api.WorkItemRef;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.api.spi.TenantContextExecutor;
import io.casehub.work.api.spi.WorkItemCreator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InboundWorkItemSchedulerImplTest {

  private WorkItemCreator creator;
  private TenantContextExecutor tenantContext;
  private InboundWorkItemSchedulerImpl scheduler;

  @BeforeEach
  void setUp() {
    creator = mock(WorkItemCreator.class);
    tenantContext = mock(TenantContextExecutor.class);
    doAnswer(inv -> {
      inv.getArgument(1, Runnable.class).run();
      return null;
    }).when(tenantContext).runInTenantContext(any(), any());
    scheduler = new InboundWorkItemSchedulerImpl(creator, tenantContext);
  }

  @Test
  void schedule_mapsAllFieldsCorrectly() {
    var expires = Instant.now().plusSeconds(3600);
    var request = InboundWorkItemRequest.builder()
        .title("Review document")
        .description("Please review the attached document")
        .candidateGroups("reviewers")
        .candidateUsers("alice,bob")
        .callerRef("case:3fa85f64-5717-4562-b3fc-2c963f66afa6/pi:step-1")
        .scope("investigations")
        .payload("{\"docId\":\"doc-123\"}")
        .tenancyId("tenant-abc")
        .createdBy("casehub-engine-inbound")
        .priority("HIGH")
        .types(List.of("review", "document"))
        .expiresAt(expires)
        .build();

    var ref = new WorkItemRef(UUID.randomUUID(), WorkItemStatus.PENDING,
        null, null, null, null, null, "tenant-abc", null, null, null, null);
    org.mockito.Mockito.when(creator.create(any())).thenReturn(ref);

    scheduler.schedule(request);

    var captor = ArgumentCaptor.forClass(WorkItemCreateRequest.class);
    verify(creator).create(captor.capture());
    var cr = captor.getValue();

    assertThat(cr.title).isEqualTo("Review document");
    assertThat(cr.description).isEqualTo("Please review the attached document");
    assertThat(cr.candidateGroups).isEqualTo("reviewers");
    assertThat(cr.candidateUsers).isEqualTo("alice,bob");
    assertThat(cr.callerRef).isEqualTo("case:3fa85f64-5717-4562-b3fc-2c963f66afa6/pi:step-1");
    assertThat(cr.scope).isEqualTo("investigations");
    assertThat(cr.payload).isEqualTo("{\"docId\":\"doc-123\"}");
    assertThat(cr.tenancyId).isEqualTo("tenant-abc");
    assertThat(cr.createdBy).isEqualTo("casehub-engine-inbound");
    assertThat(cr.priority).isEqualTo(WorkItemPriority.HIGH);
    assertThat(cr.types).containsExactly("review", "document");
    assertThat(cr.expiresAt).isEqualTo(expires);
  }

  @Test
  void schedule_nullPriority_passesNullToRequest() {
    var request = InboundWorkItemRequest.builder()
        .title("Simple task")
        .tenancyId("tenant-abc")
        .build();

    scheduler.schedule(request);

    var captor = ArgumentCaptor.forClass(WorkItemCreateRequest.class);
    verify(creator).create(captor.capture());
    assertThat(captor.getValue().priority).isNull();
  }

  @Test
  void schedule_invalidPriority_throwsIllegalArgument() {
    var request = InboundWorkItemRequest.builder()
        .title("Bad priority")
        .tenancyId("tenant-abc")
        .priority("BOGUS")
        .build();

    assertThatThrownBy(() -> scheduler.schedule(request))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void schedule_nullOptionalFields_passesNullsThrough() {
    var request = InboundWorkItemRequest.builder()
        .title("Minimal")
        .tenancyId("tenant-abc")
        .build();

    scheduler.schedule(request);

    var captor = ArgumentCaptor.forClass(WorkItemCreateRequest.class);
    verify(creator).create(captor.capture());
    var cr = captor.getValue();

    assertThat(cr.description).isNull();
    assertThat(cr.candidateGroups).isNull();
    assertThat(cr.candidateUsers).isNull();
    assertThat(cr.callerRef).isNull();
    assertThat(cr.scope).isNull();
    assertThat(cr.payload).isNull();
    assertThat(cr.createdBy).isNull();
    assertThat(cr.types).isNull();
    assertThat(cr.expiresAt).isNull();
  }

  @Test
  void schedule_executesInsideTenantContext() {
    var request = InboundWorkItemRequest.builder()
        .title("Tenant check")
        .tenancyId("tenant-xyz")
        .build();

    var capturedTenancyId = new AtomicReference<String>();
    doAnswer(inv -> {
      capturedTenancyId.set(inv.getArgument(0, String.class));
      inv.getArgument(1, Runnable.class).run();
      return null;
    }).when(tenantContext).runInTenantContext(any(), any());

    scheduler.schedule(request);

    assertThat(capturedTenancyId.get()).isEqualTo("tenant-xyz");
    verify(creator).create(any());
  }

  @Test
  void schedule_eachValidPriority_parsesCorrectly() {
    for (WorkItemPriority p : WorkItemPriority.values()) {
      var request = InboundWorkItemRequest.builder()
          .title("Priority " + p.name())
          .tenancyId("tenant-abc")
          .priority(p.name())
          .build();

      scheduler.schedule(request);
    }

    verify(creator, org.mockito.Mockito.times(WorkItemPriority.values().length))
        .create(any());
  }
}
