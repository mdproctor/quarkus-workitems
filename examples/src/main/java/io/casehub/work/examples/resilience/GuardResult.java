package io.casehub.work.examples.resilience;

public record GuardResult(
        String description,
        boolean guardTriggered,
        String errorMessage) {
}
