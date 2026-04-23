package io.quarkiverse.work.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class ClaimSlaContextTest {

    private static final Instant SUBMITTED = Instant.parse("2026-01-01T08:00:00Z");
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration TWO_HOURS = Duration.ofHours(2);
    private static final Duration THIRTY_MINUTES = Duration.ofMinutes(30);

    @Test
    void constructor_preservesAllFields() {
        final ClaimSlaContext ctx = new ClaimSlaContext(SUBMITTED, TWO_HOURS, THIRTY_MINUTES, NOW);

        assertThat(ctx.submittedAt()).isEqualTo(SUBMITTED);
        assertThat(ctx.totalPoolSlaDuration()).isEqualTo(TWO_HOURS);
        assertThat(ctx.accumulatedUnclaimedDuration()).isEqualTo(THIRTY_MINUTES);
        assertThat(ctx.now()).isEqualTo(NOW);
    }

    @Test
    void accumulatedUnclaimedDuration_zeroIsPreserved() {
        final ClaimSlaContext ctx = new ClaimSlaContext(SUBMITTED, TWO_HOURS, Duration.ZERO, NOW);
        assertThat(ctx.accumulatedUnclaimedDuration()).isEqualTo(Duration.ZERO);
    }

    @Test
    void accumulatedUnclaimedDuration_equalToTotalIsPreserved() {
        final ClaimSlaContext ctx = new ClaimSlaContext(SUBMITTED, TWO_HOURS, TWO_HOURS, NOW);
        assertThat(ctx.accumulatedUnclaimedDuration()).isEqualTo(TWO_HOURS);
    }

    @Test
    void recordEquality_sameFieldsAreEqual() {
        final ClaimSlaContext a = new ClaimSlaContext(SUBMITTED, TWO_HOURS, THIRTY_MINUTES, NOW);
        final ClaimSlaContext b = new ClaimSlaContext(SUBMITTED, TWO_HOURS, THIRTY_MINUTES, NOW);
        assertThat(a).isEqualTo(b);
    }
}
