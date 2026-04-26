package io.quarkiverse.work.runtime.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration properties for the Quarkus WorkItems extension.
 * All properties are prefixed with {@code quarkus.work}.
 *
 * <p>
 * Example {@code application.properties} overrides:
 *
 * <pre>
 * quarkus.work.default-expiry-hours=48
 * quarkus.work.escalation-policy=reassign
 * quarkus.work.cleanup.expiry-check-seconds=30
 * </pre>
 */
@ConfigMapping(prefix = "quarkus.work")
public interface WorkItemsConfig {

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

    /**
     * Business-hours calendar configuration.
     *
     * @return the business hours configuration group
     */
    @io.smallrye.config.WithName("business-hours")
    BusinessHoursConfig businessHours();

    /**
     * Business-hours configuration for SLA deadline calculation.
     */
    interface BusinessHoursConfig {

        /**
         * Timezone in which business hours are defined.
         *
         * @return IANA timezone ID; defaults to {@code "UTC"}
         */
        @WithDefault("UTC")
        String timezone();

        /**
         * Business day start time (inclusive), in HH:mm format.
         *
         * @return start time; defaults to {@code "09:00"}
         */
        @WithDefault("09:00")
        String start();

        /**
         * Business day end time (exclusive), in HH:mm format.
         *
         * @return end time; defaults to {@code "17:00"}
         */
        @WithDefault("17:00")
        String end();

        /**
         * Comma-separated working days.
         * Accepted values: {@code MON,TUE,WED,THU,FRI,SAT,SUN}.
         *
         * @return work days; defaults to {@code "MON,TUE,WED,THU,FRI"}
         */
        @WithDefault("MON,TUE,WED,THU,FRI")
        String workDays();

        /**
         * Static holiday list — comma-separated dates in {@code YYYY-MM-DD} format.
         * Ignored when {@link #holidayIcalUrl()} is set.
         * If absent, no static holidays are configured.
         *
         * @return holiday dates; empty if not configured
         */
        java.util.Optional<String> holidays();

        /**
         * URL of an iCal feed ({@code .ics}) providing public holidays.
         * When set, the iCal-backed {@code HolidayCalendar} activates and the
         * static {@link #holidays()} list is ignored.
         * If absent, iCal holidays are disabled.
         *
         * @return iCal URL; empty if not configured
         */
        java.util.Optional<String> holidayIcalUrl();
    }

    /**
     * Worker selection strategy configuration.
     *
     * @return the routing configuration group
     */
    @io.smallrye.config.WithName("routing")
    RoutingConfig routing();

    /**
     * Worker selection strategy configuration.
     */
    interface RoutingConfig {

        /**
         * The built-in worker selection strategy to use when no CDI
         * {@code @Alternative WorkerSelectionStrategy} bean is present.
         *
         * <p>
         * Accepted values:
         * <ul>
         * <li>{@code least-loaded} — pre-assigns to the candidateUser with the fewest
         * active (ASSIGNED/IN_PROGRESS/SUSPENDED) WorkItems (default)
         * <li>{@code claim-first} — no pre-assignment; pool stays open
         * </ul>
         *
         * @return strategy name; defaults to {@code "least-loaded"}
         */
        @io.smallrye.config.WithDefault("least-loaded")
        String strategy();
    }
}
