package io.quarkiverse.work.core.policy;

import java.time.Instant;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.work.api.ClaimSlaContext;
import io.quarkiverse.work.api.ClaimSlaPolicy;

/**
 * {@link ClaimSlaPolicy} with a single fixed budget from submission time.
 *
 * <p>
 * The pool deadline is always {@code submittedAt + totalPoolSlaDuration},
 * regardless of how many times the item has returned to the pool or what
 * {@code now} is. The deadline never moves.
 *
 * <p>
 * This is Approach B. Use when the SLA is an absolute contract: the item must be
 * claimed within a fixed window from when it was first raised, with no extensions.
 */
@ApplicationScoped
public class SingleBudgetPolicy implements ClaimSlaPolicy {

    @Override
    public Instant computePoolDeadline(final ClaimSlaContext context) {
        return context.submittedAt().plus(context.totalPoolSlaDuration());
    }
}
