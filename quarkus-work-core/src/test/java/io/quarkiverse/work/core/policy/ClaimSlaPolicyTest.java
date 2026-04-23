package io.quarkiverse.work.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.quarkiverse.work.api.ClaimSlaContext;

class ClaimSlaPolicyTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant SUBMITTED = Instant.parse("2026-01-01T08:00:00Z");
    private static final Duration TWO_HOURS = Duration.ofHours(2);
    private static final Duration ONE_HOUR = Duration.ofHours(1);
    private static final Duration ZERO = Duration.ZERO;

    // ---- ContinuationPolicy ------------------------------------------------

    @Test
    void continuation_halfSlaUsed_deadlineIsNowPlusHalfRemaining() {
        final var ctx = new ClaimSlaContext(SUBMITTED, TWO_HOURS, ONE_HOUR, NOW);
        final Instant deadline = new ContinuationPolicy().computePoolDeadline(ctx);
        assertThat(deadline).isEqualTo(NOW.plus(ONE_HOUR));
    }

    @Test
    void continuation_fullSlaUsed_deadlineIsNow() {
        final var ctx = new ClaimSlaContext(SUBMITTED, TWO_HOURS, TWO_HOURS, NOW);
        final Instant deadline = new ContinuationPolicy().computePoolDeadline(ctx);
        assertThat(deadline).isEqualTo(NOW);
    }

    @Test
    void continuation_overBudget_deadlineIsNow() {
        final var ctx = new ClaimSlaContext(SUBMITTED, TWO_HOURS, Duration.ofHours(3), NOW);
        final Instant deadline = new ContinuationPolicy().computePoolDeadline(ctx);
        assertThat(deadline).isEqualTo(NOW);
    }

    @Test
    void continuation_zeroAccumulated_deadlineIsNowPlusFullDuration() {
        final var ctx = new ClaimSlaContext(SUBMITTED, TWO_HOURS, ZERO, NOW);
        final Instant deadline = new ContinuationPolicy().computePoolDeadline(ctx);
        assertThat(deadline).isEqualTo(NOW.plus(TWO_HOURS));
    }

    // ---- FreshClockPolicy --------------------------------------------------

    @Test
    void fresh_halfSlaUsed_deadlineIsAlwaysNowPlusFullDuration() {
        final var ctx = new ClaimSlaContext(SUBMITTED, TWO_HOURS, ONE_HOUR, NOW);
        final Instant deadline = new FreshClockPolicy().computePoolDeadline(ctx);
        assertThat(deadline).isEqualTo(NOW.plus(TWO_HOURS));
    }

    @Test
    void fresh_fullSlaUsed_deadlineIsStillNowPlusFullDuration() {
        final var ctx = new ClaimSlaContext(SUBMITTED, TWO_HOURS, TWO_HOURS, NOW);
        final Instant deadline = new FreshClockPolicy().computePoolDeadline(ctx);
        assertThat(deadline).isEqualTo(NOW.plus(TWO_HOURS));
    }

    @Test
    void fresh_zeroAccumulated_deadlineIsNowPlusFullDuration() {
        final var ctx = new ClaimSlaContext(SUBMITTED, TWO_HOURS, ZERO, NOW);
        final Instant deadline = new FreshClockPolicy().computePoolDeadline(ctx);
        assertThat(deadline).isEqualTo(NOW.plus(TWO_HOURS));
    }

    // ---- SingleBudgetPolicy ------------------------------------------------

    @Test
    void single_zeroAccumulated_deadlineIsSubmittedPlusDuration() {
        final var ctx = new ClaimSlaContext(SUBMITTED, TWO_HOURS, ZERO, NOW);
        final Instant deadline = new SingleBudgetPolicy().computePoolDeadline(ctx);
        assertThat(deadline).isEqualTo(SUBMITTED.plus(TWO_HOURS));
    }

    @Test
    void single_halfSlaUsed_deadlineIsStillSubmittedPlusDuration() {
        final var ctx = new ClaimSlaContext(SUBMITTED, TWO_HOURS, ONE_HOUR, NOW);
        final Instant deadline = new SingleBudgetPolicy().computePoolDeadline(ctx);
        assertThat(deadline).isEqualTo(SUBMITTED.plus(TWO_HOURS));
    }

    @Test
    void single_nowIsLate_deadlineIsStillSubmittedPlusDuration() {
        // now is 5 hours after submission — deadline doesn't move
        final Instant lateNow = SUBMITTED.plus(Duration.ofHours(5));
        final var ctx = new ClaimSlaContext(SUBMITTED, TWO_HOURS, TWO_HOURS, lateNow);
        final Instant deadline = new SingleBudgetPolicy().computePoolDeadline(ctx);
        assertThat(deadline).isEqualTo(SUBMITTED.plus(TWO_HOURS));
    }

    // ---- PhaseClockPolicy --------------------------------------------------

    @Test
    void phase_withinCap_deadlineIsNowPlusFullDuration() {
        // submitted 2h ago, total = 2h, multiplier = 3 → hardCap = submitted + 6h = NOW + 4h
        // phaseDeadline = NOW + 2h — well within cap
        final var ctx = new ClaimSlaContext(SUBMITTED, TWO_HOURS, ZERO, NOW);
        final Instant deadline = new PhaseClockPolicy(3).computePoolDeadline(ctx);
        assertThat(deadline).isEqualTo(NOW.plus(TWO_HOURS));
    }

    @Test
    void phase_atCapBoundary_deadlineIsCapped() {
        // submitted 6h ago (= cap with multiplier 3 and 2h SLA), so hardCap = submitted + 6h = NOW
        final Instant submittedSixHoursAgo = NOW.minus(Duration.ofHours(6));
        final var ctx = new ClaimSlaContext(submittedSixHoursAgo, TWO_HOURS, ZERO, NOW);
        final Instant deadline = new PhaseClockPolicy(3).computePoolDeadline(ctx);
        // hardCap = submittedSixHoursAgo + 6h = NOW; phaseDeadline = NOW + 2h → cap wins
        assertThat(deadline).isEqualTo(NOW);
    }

    @Test
    void phase_pastCap_deadlineIsHardCapInPast() {
        // submitted 8h ago → hardCap is 2h in the past
        final Instant submittedEightHoursAgo = NOW.minus(Duration.ofHours(8));
        final var ctx = new ClaimSlaContext(submittedEightHoursAgo, TWO_HOURS, ZERO, NOW);
        final Instant deadline = new PhaseClockPolicy(3).computePoolDeadline(ctx);
        final Instant expectedCap = submittedEightHoursAgo.plus(Duration.ofHours(6));
        assertThat(deadline).isEqualTo(expectedCap);
    }

    @Test
    void phase_customMultiplierRespected() {
        // multiplier = 2 → hardCap = submitted + 4h = NOW + 2h
        final var ctx = new ClaimSlaContext(SUBMITTED, TWO_HOURS, ZERO, NOW);
        final Instant deadline = new PhaseClockPolicy(2).computePoolDeadline(ctx);
        // phaseDeadline = NOW + 2h; hardCap = SUBMITTED + 4h = NOW + 2h → equal, use either
        assertThat(deadline).isEqualTo(NOW.plus(TWO_HOURS));
    }

    @Test
    void phase_capMultiplierOneGivesImmediateCapAfterFirstPhase() {
        // multiplier = 1 → hardCap = submitted + 2h = NOW (submitted 2h ago)
        // phaseDeadline = NOW + 2h → cap wins → deadline is NOW
        final var ctx = new ClaimSlaContext(SUBMITTED, TWO_HOURS, ZERO, NOW);
        final Instant deadline = new PhaseClockPolicy(1).computePoolDeadline(ctx);
        assertThat(deadline).isEqualTo(SUBMITTED.plus(TWO_HOURS)); // = NOW
    }
}
