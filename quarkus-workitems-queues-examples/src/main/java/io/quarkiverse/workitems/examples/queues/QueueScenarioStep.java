package io.quarkiverse.workitems.examples.queues;

import java.util.List;
import java.util.UUID;

public record QueueScenarioStep(
        int step,
        String description,
        UUID workItemId,
        List<String> inferredLabels,
        List<String> manualLabels) {
}
