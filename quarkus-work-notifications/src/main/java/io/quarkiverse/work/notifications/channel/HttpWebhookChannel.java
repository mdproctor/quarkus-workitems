package io.quarkiverse.work.notifications.channel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.work.api.NotificationChannel;
import io.quarkiverse.work.api.NotificationPayload;
import io.quarkiverse.work.runtime.model.WorkItem;

/**
 * Outbound HTTP webhook notification channel.
 *
 * <p>
 * POSTs a JSON payload to the configured {@code targetUrl} on every matched event.
 * When a {@code secret} is configured on the rule, adds an
 * {@code X-Signature-256: sha256=<hex>} header using HMAC-SHA256.
 *
 * <p>
 * Uses {@link java.net.http.HttpClient} — no external HTTP client dependency.
 * Timeouts are fixed at 10 seconds. Delivery failures are logged and silently
 * absorbed — they must not propagate back to the WorkItem lifecycle.
 */
@ApplicationScoped
public class HttpWebhookChannel implements NotificationChannel {

    public static final String CHANNEL_TYPE = "http-webhook";

    private static final Logger LOG = Logger.getLogger(HttpWebhookChannel.class.getName());
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
            final String json = buildPayloadJson(
                    eventType,
                    wi.title,
                    wi.category,
                    wi.status != null ? wi.status.name() : null,
                    wi.assigneeId,
                    wi.priority != null ? wi.priority.name() : null,
                    wi.callerRef);

            final HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(payload.targetUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-WorkItem-Event", eventType)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));

            if (payload.secret() != null && !payload.secret().isBlank()) {
                reqBuilder.header("X-Signature-256", hmacSha256Hex(json, payload.secret()));
            }

            final HttpResponse<String> response = HTTP.send(
                    reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warning("Webhook delivery failed for rule " + payload.ruleId()
                        + " → " + payload.targetUrl() + " status=" + response.statusCode());
            }
        } catch (final Exception e) {
            LOG.warning("Webhook delivery error for rule " + payload.ruleId()
                    + " → " + payload.targetUrl() + ": " + e.getMessage());
        }
    }

    // ── package-private statics for unit testing ──────────────────────────────

    static String buildPayloadJson(
            final String eventType, final String title, final String category,
            final String status, final String assigneeId,
            final String priority, final String callerRef) {
        return "{"
                + "\"eventType\":" + quoted(eventType) + ","
                + "\"title\":" + quoted(title) + ","
                + "\"category\":" + quoted(category) + ","
                + "\"status\":" + quoted(status) + ","
                + "\"assigneeId\":" + quoted(assigneeId) + ","
                + "\"priority\":" + quoted(priority) + ","
                + "\"callerRef\":" + quoted(callerRef)
                + "}";
    }

    static String hmacSha256Hex(final String payload, final String secret) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            final byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (final Exception e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }

    private static String quoted(final String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
