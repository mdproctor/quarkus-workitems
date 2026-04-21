package io.quarkiverse.workitems.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class WorkerCandidateTest {

    @Test
    void of_createsCandidate_withEmptyCapabilitiesAndZeroCount() {
        final WorkerCandidate c = WorkerCandidate.of("alice");
        assertThat(c.id()).isEqualTo("alice");
        assertThat(c.capabilities()).isEmpty();
        assertThat(c.activeWorkItemCount()).isZero();
    }

    @Test
    void fullConstructor_setsAllFields() {
        final WorkerCandidate c = new WorkerCandidate("bob", Set.of("finance", "legal"), 3);
        assertThat(c.id()).isEqualTo("bob");
        assertThat(c.capabilities()).containsExactlyInAnyOrder("finance", "legal");
        assertThat(c.activeWorkItemCount()).isEqualTo(3);
    }

    @Test
    void withActiveWorkItemCount_returnsNewCandidateWithUpdatedCount() {
        final WorkerCandidate original = WorkerCandidate.of("carol");
        final WorkerCandidate updated = original.withActiveWorkItemCount(7);
        assertThat(updated.id()).isEqualTo("carol");
        assertThat(updated.activeWorkItemCount()).isEqualTo(7);
        assertThat(original.activeWorkItemCount()).isZero(); // immutable
    }
}
