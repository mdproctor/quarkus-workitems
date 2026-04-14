package io.quarkiverse.tarkus.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

/**
 * Quarkus build-time processor for the Tarkus extension.
 * Registers the "tarkus" feature so it appears in the startup log:
 * INFO features: [agroal, cdi, flyway, hibernate-orm, scheduler, tarkus, ...]
 *
 * Additional @BuildStep methods to add as the extension matures:
 * - Native image reflection configuration for WorkItem, WorkItemStatus, etc.
 * - Registration of the escalation policy SPI for native
 * - Flyway migration resource registration for native builds
 */
class TarkusProcessor {

    private static final String FEATURE = "tarkus";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }
}
