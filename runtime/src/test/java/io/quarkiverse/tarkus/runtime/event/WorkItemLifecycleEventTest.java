package io.quarkiverse.tarkus.runtime.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkiverse.tarkus.runtime.model.WorkItemStatus;

/**
 * Pure JUnit 5 unit tests for {@link WorkItemLifecycleEvent} — no container needed.
 * These are RED-phase TDD tests; the production class does not exist yet.
 *
 * <p>
 * Refs #25, #22
 */
class WorkItemLifecycleEventTest {

    @Test
    void of_buildsCorrectTypeString() {
        UUID id = UUID.randomUUID();
        WorkItemLifecycleEvent e = WorkItemLifecycleEvent.of("CREATED", id, WorkItemStatus.PENDING, "system", null);
        assertThat(e.type()).isEqualTo("io.quarkiverse.tarkus.workitem.created");
    }

    @Test
    void of_buildsCorrectSource() {
        UUID id = UUID.randomUUID();
        WorkItemLifecycleEvent e = WorkItemLifecycleEvent.of("ASSIGNED", id, WorkItemStatus.ASSIGNED, "alice", null);
        assertThat(e.source()).isEqualTo("/tarkus/workitems/" + id);
    }

    @Test
    void of_subjectIsWorkItemId() {
        UUID id = UUID.randomUUID();
        WorkItemLifecycleEvent e = WorkItemLifecycleEvent.of("COMPLETED", id, WorkItemStatus.COMPLETED, "alice", null);
        assertThat(e.subject()).isEqualTo(id.toString());
    }

    @Test
    void of_setsWorkItemIdAndStatus() {
        UUID id = UUID.randomUUID();
        WorkItemLifecycleEvent e = WorkItemLifecycleEvent.of("REJECTED", id, WorkItemStatus.REJECTED, "bob", "reason");
        assertThat(e.workItemId()).isEqualTo(id);
        assertThat(e.status()).isEqualTo(WorkItemStatus.REJECTED);
        assertThat(e.actor()).isEqualTo("bob");
        assertThat(e.detail()).isEqualTo("reason");
    }

    @Test
    void of_occurredAtIsNotNull() {
        WorkItemLifecycleEvent e = WorkItemLifecycleEvent.of("DELEGATED",
                UUID.randomUUID(), WorkItemStatus.PENDING, "alice", "to:bob");
        assertThat(e.occurredAt()).isNotNull();
    }

    @Test
    void of_typeIsAlwaysLowercase() {
        // "EXPIRED" → "io.quarkiverse.tarkus.workitem.expired"
        WorkItemLifecycleEvent e = WorkItemLifecycleEvent.of("EXPIRED",
                UUID.randomUUID(), WorkItemStatus.EXPIRED, "system", null);
        assertThat(e.type()).doesNotContain("EXPIRED");
        assertThat(e.type()).endsWith("expired");
    }

    @Test
    void of_nullDetailAllowed() {
        WorkItemLifecycleEvent e = WorkItemLifecycleEvent.of("CREATED",
                UUID.randomUUID(), WorkItemStatus.PENDING, "system", null);
        assertThat(e.detail()).isNull();
    }
}
