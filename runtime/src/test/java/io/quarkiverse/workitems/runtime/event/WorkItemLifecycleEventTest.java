package io.quarkiverse.workitems.runtime.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemStatus;

/**
 * Pure JUnit 5 unit tests for {@link WorkItemLifecycleEvent} — no container needed.
 *
 * <p>
 * Refs #25, #22, #118
 */
class WorkItemLifecycleEventTest {

    private WorkItem workItem(final UUID id, final WorkItemStatus status) {
        final WorkItem wi = new WorkItem();
        wi.id = id;
        wi.status = status;
        return wi;
    }

    @Test
    void of_buildsCorrectTypeString() {
        UUID id = UUID.randomUUID();
        WorkItemLifecycleEvent e = WorkItemLifecycleEvent.of("CREATED",
                workItem(id, WorkItemStatus.PENDING), "system", null);
        assertThat(e.type()).isEqualTo("io.quarkiverse.workitems.workitem.created");
    }

    @Test
    void of_buildsCorrectSourceUri() {
        UUID id = UUID.randomUUID();
        WorkItemLifecycleEvent e = WorkItemLifecycleEvent.of("ASSIGNED",
                workItem(id, WorkItemStatus.ASSIGNED), "alice", null);
        assertThat(e.sourceUri()).isEqualTo("/workitems/" + id);
    }

    @Test
    void of_sourceReturnsWorkItemEntity() {
        UUID id = UUID.randomUUID();
        WorkItem wi = workItem(id, WorkItemStatus.ASSIGNED);
        WorkItemLifecycleEvent e = WorkItemLifecycleEvent.of("ASSIGNED", wi, "alice", null);
        assertThat(e.source()).isSameAs(wi);
    }

    @Test
    void of_subjectIsWorkItemId() {
        UUID id = UUID.randomUUID();
        WorkItemLifecycleEvent e = WorkItemLifecycleEvent.of("COMPLETED",
                workItem(id, WorkItemStatus.COMPLETED), "alice", null);
        assertThat(e.subject()).isEqualTo(id.toString());
    }

    @Test
    void of_setsWorkItemIdAndStatus() {
        UUID id = UUID.randomUUID();
        WorkItemLifecycleEvent e = WorkItemLifecycleEvent.of("REJECTED",
                workItem(id, WorkItemStatus.REJECTED), "bob", "reason");
        assertThat(e.workItemId()).isEqualTo(id);
        assertThat(e.status()).isEqualTo(WorkItemStatus.REJECTED);
        assertThat(e.actor()).isEqualTo("bob");
        assertThat(e.detail()).isEqualTo("reason");
    }

    @Test
    void of_occurredAtIsNotNull() {
        WorkItemLifecycleEvent e = WorkItemLifecycleEvent.of("DELEGATED",
                workItem(UUID.randomUUID(), WorkItemStatus.PENDING), "alice", "to:bob");
        assertThat(e.occurredAt()).isNotNull();
    }

    @Test
    void of_typeIsAlwaysLowercase() {
        // "EXPIRED" -> "io.quarkiverse.workitems.workitem.expired"
        WorkItemLifecycleEvent e = WorkItemLifecycleEvent.of("EXPIRED",
                workItem(UUID.randomUUID(), WorkItemStatus.EXPIRED), "system", null);
        assertThat(e.type()).doesNotContain("EXPIRED");
        assertThat(e.type()).endsWith("expired");
    }

    @Test
    void of_nullDetailAllowed() {
        WorkItemLifecycleEvent e = WorkItemLifecycleEvent.of("CREATED",
                workItem(UUID.randomUUID(), WorkItemStatus.PENDING), "system", null);
        assertThat(e.detail()).isNull();
    }
}
