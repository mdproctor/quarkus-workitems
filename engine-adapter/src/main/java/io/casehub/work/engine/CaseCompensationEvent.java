package io.casehub.work.engine;

import io.casehub.platform.api.subscription.SubscribableEvent;

import java.util.Objects;
import java.util.UUID;

public record CaseCompensationEvent(
        Kind kind,
        String tenancyId,
        UUID caseId,
        String caseDefinitionName,
        String caseStatus,
        String actorId
) implements SubscribableEvent {

    public enum Kind { STARTED, COMPLETED, FAULTED }

    private static final String TYPE_PREFIX = "io.casehub.engine.case.compensation.";

    public CaseCompensationEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(caseId, "caseId");
    }

    @Override
    public String type() {
        return TYPE_PREFIX + kind.name().toLowerCase();
    }
}
