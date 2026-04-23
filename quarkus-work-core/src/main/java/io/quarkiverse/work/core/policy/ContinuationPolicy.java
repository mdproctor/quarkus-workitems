package io.quarkiverse.work.core.policy;

import java.time.Duration;
import java.time.Instant;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.work.api.ClaimSlaContext;
import io.quarkiverse.work.api.ClaimSlaPolicy;

/**
 * Default {@link ClaimSlaPolicy}: remaining pool time carries forward.
 *
 * <p>
 * When a WorkItem returns to the pool, the new deadline is:
 *
 * <pre>
 *   remaining = totalPoolSlaDuration - accumulatedUnclaimedDuration
 *   deadline  = now + max(remaining, ZERO)
 * </pre>
 *
 * <p>
 * If the item has already consumed all of its pool budget, the deadline is set to
 * {@code now} so escalation fires immediately on the next cleanup pass.
 *
 * <p>
 * This is Approach D — the clock pauses while the item is held by a claimant and
 * resumes where it left off when the item is returned to the pool.
 */
@ApplicationScoped
public class ContinuationPolicy implements ClaimSlaPolicy {

    @Override
    public Instant computePoolDeadline(final ClaimSlaContext context) {
        final Duration remaining = context.totalPoolSlaDuration()
                .minus(context.accumulatedUnclaimedDuration());
        if (remaining.isNegative() || remaining.isZero()) {
            return context.now();
        }
        return context.now().plus(remaining);
    }
}
