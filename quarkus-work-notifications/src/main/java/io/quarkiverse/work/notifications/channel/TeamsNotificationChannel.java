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
 * Microsoft Teams webhook notification channel.
 *
 * <p>
 * POSTs a Teams-compatible Adaptive Card JSON payload to the configured
 * incoming webhook URL. No Teams SDK required — uses the standard webhook format.
 */
@ApplicationScoped
public class TeamsNotificationChannel implements NotificationChannel {

    public static final String CHANNEL_TYPE = "teams";

    private static final Logger LOG = Logger.getLogger(TeamsNotificationChannel.class.getName());
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
            final String json = buildTeamsPayload(
                    eventType, wi.title, wi.category,
                    wi.status != null ? wi.status.name() : null,
                    wi.assigneeId, wi.priority != null ? wi.priority.name() : null);

            final HttpResponse<String> response = HTTP.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(payload.targetUrl()))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warning("Teams delivery failed for rule " + payload.ruleId()
                        + " status=" + response.statusCode());
            }
        } catch (final Exception e) {
            LOG.warning("Teams delivery error for rule " + payload.ruleId()
                    + ": " + e.getMessage());
        }
    }

    // ── package-private for unit testing ─────────────────────────────────────

    static String buildTeamsPayload(final String eventType, final String title,
            final String category, final String status,
            final String assigneeId, final String priority) {
        return "{"
                + "\"type\":\"message\","
                + "\"attachments\":[{"
                + "\"contentType\":\"application/vnd.microsoft.card.adaptive\","
                + "\"content\":{"
                + "\"type\":\"AdaptiveCard\","
                + "\"version\":\"1.4\","
                + "\"body\":[{"
                + "\"type\":\"TextBlock\","
                + "\"text\":" + q("[" + esc(eventType) + "] " + esc(title)) + ","
                + "\"weight\":\"Bolder\","
                + "\"size\":\"Medium\""
                + "},{"
                + "\"type\":\"FactSet\","
                + "\"facts\":["
                + fact("Category", category)
                + "," + fact("Status", status)
                + "," + fact("Priority", priority)
                + "," + fact("Assignee", assigneeId)
                + "]"
                + "}]"
                + "}"
                + "}]"
                + "}";
    }

    private static String fact(final String name, final String value) {
        return "{\"title\":" + q(name) + ",\"value\":" + q(value != null ? value : "—") + "}";
    }

    private static String q(final String s) {
        if (s == null)
            return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String esc(final String s) {
        return s != null ? s : "";
    }
}
