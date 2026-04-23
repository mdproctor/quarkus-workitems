package io.quarkiverse.work.api;

import java.time.Duration;
import java.time.Instant;

/**
 * Context passed to {@link ClaimSlaPolicy#computePoolDeadline} when a WorkItem
 * returns to the unclaimed pool.
 *
 * @param submittedAt when the WorkItem was first created
 * @param totalPoolSlaDuration the configured pool SLA (e.g. Duration.ofHours(2))
 * @param accumulatedUnclaimedDuration total time the item has already spent unclaimed
 *        across all previous unclaimed phases
 * @param now current instant (passed in for testability)
 */
public record ClaimSlaContext(
        Instant submittedAt,
        Duration totalPoolSlaDuration,
        Duration accumulatedUnclaimedDuration,
        Instant now) {
}
