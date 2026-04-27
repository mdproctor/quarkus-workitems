package io.quarkiverse.work.runtime.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.work.runtime.model.WorkItem;
import io.quarkiverse.work.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.work.runtime.model.WorkItemPriority;
import io.quarkiverse.work.runtime.model.WorkItemStatus;
import io.quarkiverse.work.runtime.repository.WorkItemStore;
import io.quarkiverse.work.runtime.service.ClaimDeadlineJob;
import io.quarkiverse.work.runtime.service.ExpiryCleanupJob;
import io.quarkiverse.work.runtime.service.WorkItemService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * @QuarkusTest CDI event emission tests — verifies that WorkItemService and the scheduler
 *              jobs fire {@link WorkItemLifecycleEvent} for every lifecycle transition.
 *
 *              <p>
 *              These are RED-phase TDD tests; WorkItemLifecycleEvent and its firing
 *              infrastructure do not exist yet.
 *
 *              <p>
 *              Refs #25, #23, #24
 */
@QuarkusTest
@TestTransaction
class WorkItemEventTest {

    @Inject
    WorkItemService service;

    @Inject
    WorkItemStore workItemStore;

    @Inject
    ExpiryCleanupJob expiryCleanupJob;

    @Inject
    ClaimDeadlineJob claimDeadlineJob;

    @Inject
    TestEventObserver observer;

    @BeforeEach
    void clearEvents() {
        observer.clear();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private WorkItemCreateRequest basicRequest() {
        return new WorkItemCreateRequest(
                "Test", null, null, null, WorkItemPriority.NORMAL,
                null, null, null, null, "system", null, null, null, null, null, null, null, null, null);
    }

    // -------------------------------------------------------------------------
    // WorkItemService events — one per operation
    // -------------------------------------------------------------------------

    @Test
    void create_emitsCreatedEvent() {
        WorkItem wi = service.create(basicRequest());

        List<WorkItemLifecycleEvent> events = observer.getEvents();
        assertThat(events).hasSize(1);
        WorkItemLifecycleEvent e = events.get(0);
        assertThat(e.type()).endsWith("created");
        assertThat(e.status()).isEqualTo(WorkItemStatus.PENDING);
        assertThat(e.actor()).isEqualTo("system");
        assertThat(e.workItemId()).isEqualTo(wi.id);
    }

    @Test
    void claim_emitsAssignedEvent() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");

        List<WorkItemLifecycleEvent> events = observer.getEvents();
        WorkItemLifecycleEvent last = events.get(events.size() - 1);
        assertThat(last.type()).endsWith("assigned");
        assertThat(last.status()).isEqualTo(WorkItemStatus.ASSIGNED);
        assertThat(last.actor()).isEqualTo("alice");
    }

    @Test
    void start_emitsStartedEvent() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");

        List<WorkItemLifecycleEvent> events = observer.getEvents();
        WorkItemLifecycleEvent last = events.get(events.size() - 1);
        assertThat(last.type()).endsWith("started");
        assertThat(last.status()).isEqualTo(WorkItemStatus.IN_PROGRESS);
    }

    @Test
    void complete_emitsCompletedEvent() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        service.complete(wi.id, "alice", "done");

        List<WorkItemLifecycleEvent> events = observer.getEvents();
        WorkItemLifecycleEvent last = events.get(events.size() - 1);
        assertThat(last.type()).endsWith("completed");
        assertThat(last.status()).isEqualTo(WorkItemStatus.COMPLETED);
    }

    @Test
    void reject_emitsRejectedEvent() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.reject(wi.id, "alice", "not applicable");

        List<WorkItemLifecycleEvent> events = observer.getEvents();
        WorkItemLifecycleEvent last = events.get(events.size() - 1);
        assertThat(last.type()).endsWith("rejected");
        assertThat(last.status()).isEqualTo(WorkItemStatus.REJECTED);
    }

    @Test
    void delegate_emitsDelegatedEvent() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.delegate(wi.id, "alice", "bob");

        List<WorkItemLifecycleEvent> events = observer.getEvents();
        WorkItemLifecycleEvent last = events.get(events.size() - 1);
        assertThat(last.type()).endsWith("delegated");
        assertThat(last.status()).isEqualTo(WorkItemStatus.PENDING);
        assertThat(last.detail()).contains("bob");
    }

    @Test
    void release_emitsReleasedEvent() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.release(wi.id, "alice");

        List<WorkItemLifecycleEvent> events = observer.getEvents();
        WorkItemLifecycleEvent last = events.get(events.size() - 1);
        assertThat(last.type()).endsWith("released");
        assertThat(last.status()).isEqualTo(WorkItemStatus.PENDING);
    }

    @Test
    void suspend_emitsSuspendedEvent() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.suspend(wi.id, "alice", "blocked");

        List<WorkItemLifecycleEvent> events = observer.getEvents();
        WorkItemLifecycleEvent last = events.get(events.size() - 1);
        assertThat(last.type()).endsWith("suspended");
        assertThat(last.status()).isEqualTo(WorkItemStatus.SUSPENDED);
    }

    @Test
    void resume_emitsResumedEvent() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.suspend(wi.id, "alice", "blocked");
        service.resume(wi.id, "alice");

        List<WorkItemLifecycleEvent> events = observer.getEvents();
        WorkItemLifecycleEvent last = events.get(events.size() - 1);
        assertThat(last.type()).endsWith("resumed");
    }

    @Test
    void cancel_emitsCancelledEvent() {
        WorkItem wi = service.create(basicRequest());
        service.cancel(wi.id, "admin", "no longer needed");

        List<WorkItemLifecycleEvent> events = observer.getEvents();
        WorkItemLifecycleEvent last = events.get(events.size() - 1);
        assertThat(last.type()).endsWith("cancelled");
        assertThat(last.status()).isEqualTo(WorkItemStatus.CANCELLED);
    }

    // -------------------------------------------------------------------------
    // Event count and ordering
    // -------------------------------------------------------------------------

    @Test
    void fullHappyPath_emitsFourEventsInOrder() {
        WorkItem wi = service.create(basicRequest());
        service.claim(wi.id, "alice");
        service.start(wi.id, "alice");
        service.complete(wi.id, "alice", "done");

        List<WorkItemLifecycleEvent> events = observer.getEvents();
        assertThat(events).hasSize(4);
        assertThat(events.get(0).type()).endsWith("created");
        assertThat(events.get(1).type()).endsWith("assigned");
        assertThat(events.get(2).type()).endsWith("started");
        assertThat(events.get(3).type()).endsWith("completed");
    }

    @Test
    void noEventsOnFailedTransition() {
        WorkItem wi = service.create(basicRequest());
        // At this point: 1 CREATED event

        // Attempt an invalid transition — PENDING cannot be completed directly
        Throwable thrown = catchThrowable(() -> service.complete(wi.id, "alice", "done"));
        assertThat(thrown).isInstanceOf(IllegalStateException.class);

        // No additional event should have been fired
        assertThat(observer.getEvents()).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Scheduler job events
    // -------------------------------------------------------------------------

    @Test
    void expiryJob_emitsExpiredEvent() {
        WorkItem wi = new WorkItem();
        wi.title = "Past expiry";
        wi.status = WorkItemStatus.PENDING;
        wi.priority = WorkItemPriority.NORMAL;
        wi.createdAt = Instant.now();
        wi.updatedAt = Instant.now();
        wi.expiresAt = Instant.now().minus(2, ChronoUnit.HOURS);
        workItemStore.put(wi);
        observer.clear(); // discard any save-triggered events

        expiryCleanupJob.checkExpired();

        List<WorkItemLifecycleEvent> expiredEvents = observer.ofType("expired");
        assertThat(expiredEvents).isNotEmpty();
        assertThat(expiredEvents).anyMatch(e -> e.workItemId().equals(wi.id));
    }

    @Test
    void expiryJob_emitsEscalatedEventAfterExpired() {
        WorkItem wi = new WorkItem();
        wi.title = "Past expiry escalate";
        wi.status = WorkItemStatus.PENDING;
        wi.priority = WorkItemPriority.NORMAL;
        wi.createdAt = Instant.now();
        wi.updatedAt = Instant.now();
        wi.expiresAt = Instant.now().minus(2, ChronoUnit.HOURS);
        workItemStore.put(wi);
        observer.clear();

        expiryCleanupJob.checkExpired();

        List<WorkItemLifecycleEvent> expiredEvents = observer.ofType("expired");
        List<WorkItemLifecycleEvent> escalatedEvents = observer.ofType("escalated");
        assertThat(expiredEvents).anyMatch(e -> e.workItemId().equals(wi.id));
        assertThat(escalatedEvents).anyMatch(e -> e.workItemId().equals(wi.id));
    }

    @Test
    void claimDeadlineJob_emitsClaimExpiredEvent() {
        WorkItem wi = new WorkItem();
        wi.title = "Past claim deadline";
        wi.status = WorkItemStatus.PENDING;
        wi.priority = WorkItemPriority.NORMAL;
        wi.createdAt = Instant.now();
        wi.updatedAt = Instant.now();
        wi.claimDeadline = Instant.now().minus(1, ChronoUnit.HOURS);
        workItemStore.put(wi);
        observer.clear();

        claimDeadlineJob.checkUnclaimedPastDeadline();

        // ClaimDeadlineJob now fires CLAIM_EXPIRED (not generic ESCALATED)
        List<WorkItemLifecycleEvent> claimExpiredEvents = observer.ofType("claim_expired");
        assertThat(claimExpiredEvents).anyMatch(e -> e.workItemId().equals(wi.id));
    }

    // -------------------------------------------------------------------------
    // Source and subject format
    // -------------------------------------------------------------------------

    @Test
    void event_sourceContainsWorkItemId() {
        WorkItem wi = service.create(basicRequest());

        List<WorkItemLifecycleEvent> events = observer.getEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).sourceUri()).isEqualTo("/workitems/" + wi.id);
    }

    @Test
    void event_subjectIsWorkItemId() {
        WorkItem wi = service.create(basicRequest());

        List<WorkItemLifecycleEvent> events = observer.getEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).subject()).isEqualTo(wi.id.toString());
    }
}
