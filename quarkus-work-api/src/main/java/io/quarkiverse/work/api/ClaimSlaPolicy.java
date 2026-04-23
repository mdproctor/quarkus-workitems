package io.quarkiverse.work.api;

import java.time.Instant;

/**
 * SPI for computing the pool-phase deadline when a WorkItem returns to the
 * unclaimed pool after a claim expires, an unclaim, or a transfer.
 *
 * <p>
 * Implement as {@code @ApplicationScoped @Alternative @Priority(1)} to override
 * the built-in policy. Built-in implementations (in quarkus-work-core):
 * <ul>
 * <li>{@code ContinuationPolicy} — remaining pool time carries forward (default)
 * <li>{@code FreshClockPolicy} — full pool SLA resets on every return to pool
 * <li>{@code SingleBudgetPolicy} — hard deadline from submission, never moves
 * <li>{@code PhaseClockPolicy} — each claimant gets full time; hard total cap above
 * </ul>
 */
public interface ClaimSlaPolicy {

    /**
     * Compute the pool-phase deadline for a WorkItem that has just returned to
     * the unclaimed pool.
     *
     * @param context transition context; never null
     * @return the absolute deadline instant for the next unclaimed phase; never null.
     *         Return {@code Instant.MAX} to indicate no deadline (no escalation will fire).
     */
    Instant computePoolDeadline(ClaimSlaContext context);
}
