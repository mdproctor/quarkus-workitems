package io.quarkiverse.work.notifications.channel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.work.api.NotificationChannel;
import io.quarkiverse.work.api.NotificationPayload;
import io.quarkiverse.work.runtime.model.WorkItem;

/**
 * Slack Incoming Webhooks notification channel.
 *
 * <p>
 * POSTs a Slack-compatible JSON message to the configured webhook URL.
 * Message format: {@code "[{eventType}] {title} ({category}) — {status}"}.
 *
 * <p>
 * No Slack SDK dependency — uses the standard Incoming Webhooks JSON format
 * and {@link java.net.http.HttpClient}.
 */
@ApplicationScoped
public class SlackNotificationChannel implements NotificationChannel {

    public static final String CHANNEL_TYPE = "slack";

    private static final Logger LOG = Logger.getLogger(SlackNotificationChannel.class.getName());
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public String channelType() {
        return CHANNEL_TYPE;
    }

    @Override
    public void send(final NotificationPayload payload) {
        try {
            final WorkItem wi = (WorkItem) payload.event().source();
            final String eventType = payload.event().eventType().name();
            final String json = buildSlackPayload(
                    eventType, wi.title, wi.category,
                    wi.status != null ? wi.status.name() : null,
                    wi.assigneeId);

            final HttpResponse<String> response = HTTP.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(payload.targetUrl()))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warning("Slack delivery failed for rule " + payload.ruleId()
                        + " status=" + response.statusCode());
            }
        } catch (final Exception e) {
            LOG.warning("Slack delivery error for rule " + payload.ruleId()
                    + ": " + e.getMessage());
        }
    }

    // ── package-private for unit testing ─────────────────────────────────────

    static String buildSlackPayload(final String eventType, final String title,
            final String category, final String status, final String assigneeId) {
        final String assignee = assigneeId != null ? " → " + escape(assigneeId) : "";
        final String text = "*[" + escape(eventType) + "]* "
                + escape(title) + " (" + escape(category) + ")"
                + " — " + escape(status) + assignee;
        return "{\"text\":" + quoted(text) + "}";
    }

    private static String escape(final String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String quoted(final String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
