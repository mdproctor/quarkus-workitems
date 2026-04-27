package io.quarkiverse.work.notifications.model;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * A rule that fires a notification on specific WorkItem lifecycle events.
 *
 * <h2>Matching semantics</h2>
 * <ul>
 * <li>{@link #eventTypes} — comma-separated {@code WorkEventType} names. A rule
 * matches when the event type appears in this list.</li>
 * <li>{@link #category} — optional WorkItem category filter. {@code null} means
 * match all categories.</li>
 * <li>{@link #enabled} — disabled rules are never evaluated.</li>
 * </ul>
 *
 * <h2>Delivery</h2>
 * <p>
 * When a rule matches, {@code NotificationDispatcher} calls the {@link
 * io.quarkiverse.work.api.NotificationChannel} CDI bean whose {@code channelType()}
 * equals {@link #channelType} with a {@link io.quarkiverse.work.api.NotificationPayload}
 * built from this rule and the lifecycle event.
 */
@Entity
@Table(name = "work_item_notification_rule")
public class WorkItemNotificationRule extends PanacheEntityBase {

    @Id
    public UUID id;

    /**
     * The notification channel to use — matched against
     * {@link io.quarkiverse.work.api.NotificationChannel#channelType()}.
     * Built-in values: {@code "http-webhook"}, {@code "slack"}, {@code "teams"}.
     */
    @Column(name = "channel_type", nullable = false, length = 50)
    public String channelType;

    /**
     * The destination URL for this channel (Slack webhook URL, Teams webhook URL,
     * or generic HTTP endpoint).
     */
    @Column(name = "target_url", nullable = false, length = 2048)
    public String targetUrl;

    /**
     * Comma-separated {@code WorkEventType} names that trigger this rule.
     * Example: {@code "ASSIGNED,COMPLETED,REJECTED"}.
     */
    @Column(name = "event_types", nullable = false, length = 500)
    public String eventTypes;

    /**
     * Optional category filter. {@code null} matches all categories.
     * Example: {@code "loan-application"}.
     */
    @Column(name = "category", length = 255)
    public String category;

    /**
     * Optional HMAC-SHA256 secret for signed HTTP webhook delivery.
     * When set, the dispatcher adds an {@code X-Signature-256} header.
     * Not used by Slack or Teams channels.
     */
    @Column(name = "secret", length = 255)
    public String secret;

    /** When {@code false}, this rule is never evaluated. Default {@code true}. */
    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * Returns the event types as a parsed list — the stored value is a
     * comma-separated string for simplicity.
     */
    public List<String> parsedEventTypes() {
        if (eventTypes == null || eventTypes.isBlank()) {
            return List.of();
        }
        return Arrays.stream(eventTypes.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    // ── finders ──────────────────────────────────────────────────────────────

    /** All enabled rules, ordered by creation time. */
    public static List<WorkItemNotificationRule> findAllEnabled() {
        return list("enabled = true ORDER BY createdAt ASC");
    }

    /**
     * Enabled rules that match a given event type.
     * Category filtering is done in-memory after this query.
     */
    public static List<WorkItemNotificationRule> findEnabledForEventType(final String eventType) {
        // eventTypes is a comma-separated string; use LIKE for a simple contains check.
        // Works for exact names like "ASSIGNED" without collision because event type
        // names use only uppercase letters (no substring overlap).
        return list("enabled = true AND eventTypes LIKE ?1 ORDER BY createdAt ASC",
                "%" + eventType + "%");
    }
}
