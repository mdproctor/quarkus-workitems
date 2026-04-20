package io.quarkiverse.workitems.runtime.api;

import java.util.Map;

import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps {@link OptimisticLockException} to HTTP 409 Conflict.
 *
 * <h2>When this fires</h2>
 * <p>
 * Two Quarkus nodes simultaneously claim the same PENDING WorkItem. The first
 * succeeds and bumps the JPA {@code @Version} field. The second attempt's UPDATE
 * includes {@code WHERE version = N} which matches zero rows — Hibernate throws
 * {@code OptimisticLockException}. This mapper converts that to 409 Conflict
 * with a retry-friendly error message.
 *
 * <h2>What callers should do</h2>
 * <p>
 * On 409, reload the WorkItem ({@code GET /workitems/{id}}) and retry the operation.
 * If the WorkItem is now ASSIGNED (someone else claimed it), the retry is unnecessary.
 * If it is still PENDING, retry the claim with the latest version reflected in the
 * reloaded entity.
 *
 * <h2>Scope</h2>
 * <p>
 * This mapper applies to any REST endpoint that modifies a WorkItem — not just claim.
 * Start, complete, reject, and delegate also write to the WorkItem and benefit from
 * the same protection, even though claim is the most common concurrent operation.
 */
@Provider
public class OptimisticLockExceptionMapper implements ExceptionMapper<OptimisticLockException> {

    @Override
    public Response toResponse(final OptimisticLockException e) {
        return Response.status(Response.Status.CONFLICT)
                .entity(Map.of(
                        "error", "Concurrent modification conflict — the WorkItem was modified by " +
                                "another request. Reload and retry.",
                        "hint", "GET the WorkItem to check its current state, then retry if still applicable"))
                .build();
    }
}
