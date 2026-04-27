package io.quarkiverse.work.notifications.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for HMAC signing and payload building — no HTTP, no CDI.
 */
class HttpWebhookChannelTest {

    @Test
    void hmacSignature_generatedCorrectly() {
        final String secret = "test-secret";
        final String payload = "{\"test\":\"value\"}";
        final String sig = HttpWebhookChannel.hmacSha256Hex(payload, secret);
        assertThat(sig).startsWith("sha256=");
        assertThat(sig).hasSize(7 + 64); // "sha256=" + 64 hex chars
    }

    @Test
    void hmacSignature_deterministicForSameInput() {
        final String secret = "my-secret";
        final String payload = "{\"workItemId\":\"abc\"}";
        assertThat(HttpWebhookChannel.hmacSha256Hex(payload, secret))
                .isEqualTo(HttpWebhookChannel.hmacSha256Hex(payload, secret));
    }

    @Test
    void hmacSignature_differentForDifferentPayload() {
        final String secret = "my-secret";
        assertThat(HttpWebhookChannel.hmacSha256Hex("payload1", secret))
                .isNotEqualTo(HttpWebhookChannel.hmacSha256Hex("payload2", secret));
    }

    @Test
    void hmacSignature_differentForDifferentSecret() {
        final String payload = "same-payload";
        assertThat(HttpWebhookChannel.hmacSha256Hex(payload, "secret1"))
                .isNotEqualTo(HttpWebhookChannel.hmacSha256Hex(payload, "secret2"));
    }

    @Test
    void buildPayloadJson_containsRequiredFields() {
        final var json = HttpWebhookChannel.buildPayloadJson("ASSIGNED", "Loan review",
                "loan", "ASSIGNED", "alice", "HIGH", "ref-1");
        assertThat(json).contains("\"eventType\":\"ASSIGNED\"");
        assertThat(json).contains("\"title\":\"Loan review\"");
        assertThat(json).contains("\"category\":\"loan\"");
        assertThat(json).contains("\"status\":\"ASSIGNED\"");
        assertThat(json).contains("\"assigneeId\":\"alice\"");
        assertThat(json).contains("\"priority\":\"HIGH\"");
        assertThat(json).contains("\"callerRef\":\"ref-1\"");
    }

    @Test
    void buildPayloadJson_nullFieldsHandled() {
        assertThatCode(() -> HttpWebhookChannel.buildPayloadJson(
                "CREATED", "Title", "cat", "PENDING", null, "NORMAL", null))
                .doesNotThrowAnyException();
    }

    @Test
    void channelType_isHttpWebhook() {
        // Verify the constant without needing CDI
        assertThat(HttpWebhookChannel.CHANNEL_TYPE).isEqualTo("http-webhook");
    }
}
