package io.quarkiverse.work.core.policy;

import java.time.Instant;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.work.api.ClaimSlaContext;
import io.quarkiverse.work.api.ClaimSlaPolicy;

/**
 * {@link ClaimSlaPolicy} that grants each pool phase a full window but enforces a hard cap.
 *
 * <p>
 * Each return to the unclaimed pool gives the full {@code totalPoolSlaDuration} from now.
 * A hard cap of {@code submittedAt + (totalPoolSlaDuration * capMultiplier)} prevents the
 * total unclaimed time from growing without bound across many claim/return cycles.
 *
 * <pre>
 * deadline = min(now + totalPoolSlaDuration, submittedAt + totalPoolSlaDuration * capMultiplier)
 * </pre>
 *
 * <p>
 * This is Approach C. Suitable when you want to be generous to individual claimants
 * (each gets the full window) but still enforce an absolute outer bound.
 */
@ApplicationScoped
public class PhaseClockPolicy implements ClaimSlaPolicy {

    private final int capMultiplier;

    /** CDI constructor — uses the default cap multiplier of 3. */
    public PhaseClockPolicy() {
        this(3);
    }

    /** Test constructor — inject a custom multiplier. */
    PhaseClockPolicy(final int capMultiplier) {
        this.capMultiplier = capMultiplier;
    }

    @Override
    public Instant computePoolDeadline(final ClaimSlaContext context) {
        final Instant phaseDeadline = context.now().plus(context.totalPoolSlaDuration());
        final Instant hardCap = context.submittedAt()
                .plus(context.totalPoolSlaDuration().multipliedBy(capMultiplier));
        return phaseDeadline.isBefore(hardCap) ? phaseDeadline : hardCap;
    }
}
