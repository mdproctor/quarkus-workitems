package io.quarkiverse.workitems.runtime.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemStatus;

/**
 * Pure unit tests for {@link WorkItemEventBroadcaster} — no Quarkus, no CDI.
 *
 * <p>
 * Verifies the broadcaster's filtering logic and hot-stream delivery semantics
 * without spinning up the full runtime.
 */
class WorkItemEventBroadcasterTest {

    private WorkItemEventBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new WorkItemEventBroadcaster();
    }

    // ── Basic delivery ────────────────────────────────────────────────────────

    @Test
    void broadcast_deliversEvent_toSubscriber() throws InterruptedException {
        final List<WorkItemLifecycleEvent> received = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);

        broadcaster.stream(null, null)
                .subscribe().with(e -> {
                    received.add(e);
                    latch.countDown();
                });

        broadcaster.onEvent(event("CREATED", UUID.randomUUID()));

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).type()).endsWith("created");
    }

    @Test
    void broadcast_deliversToMultipleSubscribers() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(2);
        final List<WorkItemLifecycleEvent> sub1 = new CopyOnWriteArrayList<>();
        final List<WorkItemLifecycleEvent> sub2 = new CopyOnWriteArrayList<>();

        broadcaster.stream(null, null).subscribe().with(e -> {
            sub1.add(e);
            latch.countDown();
        });
        broadcaster.stream(null, null).subscribe().with(e -> {
            sub2.add(e);
            latch.countDown();
        });

        broadcaster.onEvent(event("ASSIGNED", UUID.randomUUID()));

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(sub1).hasSize(1);
        assertThat(sub2).hasSize(1);
    }

    @Test
    void broadcast_multipleEvents_inOrder() throws InterruptedException {
        final UUID id = UUID.randomUUID();
        final List<String> types = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(3);

        broadcaster.stream(null, null).subscribe().with(e -> {
            types.add(e.type());
            latch.countDown();
        });

        broadcaster.onEvent(event("CREATED", id));
        broadcaster.onEvent(event("ASSIGNED", id));
        broadcaster.onEvent(event("COMPLETED", id));

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(types).extracting(t -> t.substring(t.lastIndexOf('.') + 1))
                .containsExactly("created", "assigned", "completed");
    }

    // ── workItemId filter ─────────────────────────────────────────────────────

    @Test
    void filter_byWorkItemId_onlyDeliversMatchingEvents() throws InterruptedException {
        final UUID target = UUID.randomUUID();
        final UUID other = UUID.randomUUID();
        final List<WorkItemLifecycleEvent> received = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);

        broadcaster.stream(target, null).subscribe().with(e -> {
            received.add(e);
            latch.countDown();
        });

        broadcaster.onEvent(event("CREATED", other)); // should NOT be received
        broadcaster.onEvent(event("CREATED", target)); // should be received

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).workItemId()).isEqualTo(target);
    }

    @Test
    void filter_byWorkItemId_null_receivesAll() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(2);
        final List<WorkItemLifecycleEvent> received = new CopyOnWriteArrayList<>();

        broadcaster.stream(null, null).subscribe().with(e -> {
            received.add(e);
            latch.countDown();
        });

        broadcaster.onEvent(event("CREATED", UUID.randomUUID()));
        broadcaster.onEvent(event("CREATED", UUID.randomUUID()));

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(2);
    }

    // ── type filter ───────────────────────────────────────────────────────────

    @Test
    void filter_byType_onlyDeliversMatchingEvents() throws InterruptedException {
        final List<WorkItemLifecycleEvent> received = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);

        broadcaster.stream(null, "completed").subscribe().with(e -> {
            received.add(e);
            latch.countDown();
        });

        broadcaster.onEvent(event("CREATED", UUID.randomUUID())); // filtered out
        broadcaster.onEvent(event("ASSIGNED", UUID.randomUUID())); // filtered out
        broadcaster.onEvent(event("COMPLETED", UUID.randomUUID())); // matches

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).type()).endsWith("completed");
    }

    @Test
    void filter_byType_caseInsensitive() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final List<WorkItemLifecycleEvent> received = new CopyOnWriteArrayList<>();

        broadcaster.stream(null, "CREATED").subscribe().with(e -> {
            received.add(e);
            latch.countDown();
        });

        broadcaster.onEvent(event("CREATED", UUID.randomUUID()));

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
    }

    @Test
    void filter_combined_workItemIdAndType() throws InterruptedException {
        final UUID target = UUID.randomUUID();
        final CountDownLatch latch = new CountDownLatch(1);
        final List<WorkItemLifecycleEvent> received = new CopyOnWriteArrayList<>();

        broadcaster.stream(target, "completed").subscribe().with(e -> {
            received.add(e);
            latch.countDown();
        });

        broadcaster.onEvent(event("CREATED", target)); // wrong type
        broadcaster.onEvent(event("COMPLETED", UUID.randomUUID())); // wrong id
        broadcaster.onEvent(event("COMPLETED", target)); // matches both

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).workItemId()).isEqualTo(target);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private WorkItemLifecycleEvent event(final String name, final UUID workItemId) {
        final WorkItem wi = new WorkItem();
        wi.id = workItemId;
        wi.status = WorkItemStatus.PENDING;
        return WorkItemLifecycleEvent.of(name, wi, "test", null);
    }
}
