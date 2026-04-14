package io.quarkiverse.tarkus.runtime.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration properties for the Quarkus Tarkus extension.
 * All properties are prefixed with {@code quarkus.tarkus}.
 *
 * <p>
 * Example {@code application.properties} overrides:
 *
 * <pre>
 * quarkus.tarkus.default-expiry-hours=48
 * quarkus.tarkus.escalation-policy=reassign
 * quarkus.tarkus.cleanup.expiry-check-seconds=30
 * </pre>
 */
@ConfigMapping(prefix = "quarkus.tarkus")
public interface TarkusConfig {

    /**
     * Default number of hours before a WorkItem's completion deadline ({@code expiresAt})
     * is set, when no explicit {@code expiresAt} is provided at creation time.
     *
     * @return number of hours; defaults to {@code 24}
     */
    @WithDefault("24")
    int defaultExpiryHours();

    /**
     * Default number of hours before an unclaimed WorkItem must be claimed
     * ({@code claimDeadline}).
     * Set to {@code 0} to disable claim deadlines by default.
     *
     * @return number of hours; defaults to {@code 4}
     */
    @WithDefault("4")
    int defaultClaimHours();

    /**
     * Escalation policy applied when a WorkItem's completion deadline ({@code expiresAt})
     * passes without resolution.
     * Accepted values: {@code notify}, {@code reassign}, {@code auto-reject}.
     *
     * @return policy name; defaults to {@code "notify"}
     */
    @WithDefault("notify")
    String escalationPolicy();

    /**
     * Escalation policy applied when a WorkItem's claim deadline passes without
     * the item being claimed.
     * Accepted values: {@code notify}, {@code reassign}.
     *
     * @return policy name; defaults to {@code "notify"}
     */
    @WithDefault("notify")
    String claimEscalationPolicy();

    /**
     * Expiry and claim-deadline cleanup job configuration.
     *
     * @return the cleanup configuration group
     */
    CleanupConfig cleanup();

    /**
     * Cleanup job configuration for expiry and claim-deadline deadline scanning.
     */
    interface CleanupConfig {

        /**
         * How often, in seconds, the expiry and claim-deadline cleanup job polls for
         * WorkItems that have breached their deadlines.
         *
         * @return poll interval in seconds; defaults to {@code 60}
         */
        @WithDefault("60")
        int expiryCheckSeconds();
    }
}
