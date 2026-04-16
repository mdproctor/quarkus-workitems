package io.quarkiverse.workitems.examples.queues;

import java.util.List;
import java.util.UUID;

public record QueueScenarioResponse(
        String scenarioId,
        String description,
        List<QueueScenarioStep> steps,
        List<UUID> queueContents) {
}
