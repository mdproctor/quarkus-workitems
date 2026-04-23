package io.quarkiverse.work.core.policy;

import java.time.Instant;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.work.api.ClaimSlaContext;
import io.quarkiverse.work.api.ClaimSlaPolicy;

/**
 * {@link ClaimSlaPolicy} that resets the full pool window on every return to pool.
 *
 * <p>
 * Each time a WorkItem returns to the unclaimed pool, it gets the full
 * {@code totalPoolSlaDuration} from now — regardless of how much time has
 * previously been spent unclaimed.
 *
 * <p>
 * This is Approach A. Use when each claim attempt should be treated as a fresh
 * opportunity, and accumulated history should not affect the next window.
 */
@ApplicationScoped
public class FreshClockPolicy implements ClaimSlaPolicy {

    @Override
    public Instant computePoolDeadline(final ClaimSlaContext context) {
        return context.now().plus(context.totalPoolSlaDuration());
    }
}
