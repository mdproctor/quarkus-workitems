package io.casehub.work.examples.resilience;

import java.util.List;
import java.util.UUID;

public record LifecycleResult(
        UUID originalId,
        UUID compensatingId,
        List<String> compensatingStatuses,
        String finalCompensationStatus) {
}
