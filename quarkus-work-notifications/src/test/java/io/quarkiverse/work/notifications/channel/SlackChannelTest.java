package io.quarkiverse.work.notifications.channel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for Slack payload building — no HTTP, no CDI.
 */
class SlackChannelTest {

    @Test
    void buildSlackPayload_containsEventAndTitle() {
        final String json = SlackNotificationChannel.buildSlackPayload(
                "ASSIGNED", "Loan review", "loan-application", "ASSIGNED", "alice");
        assertThat(json).contains("ASSIGNED");
        assertThat(json).contains("Loan review");
        assertThat(json).contains("loan-application");
    }

    @Test
    void buildSlackPayload_validJson() {
        final String json = SlackNotificationChannel.buildSlackPayload(
                "COMPLETED", "Review done", "legal", "COMPLETED", null);
        // Must be valid JSON (starts with { ends with })
        assertThat(json.trim()).startsWith("{").endsWith("}");
        assertThat(json).contains("\"text\"");
    }

    @Test
    void buildSlackPayload_escapesSpecialChars() {
        // Title with double quotes should not break JSON
        final String json = SlackNotificationChannel.buildSlackPayload(
                "CREATED", "Review \"special\" item", "cat", "PENDING", null);
        // The JSON must still be structurally valid (quotes escaped)
        assertThat(json).doesNotContain("\"Review \"special\"");
    }

    @Test
    void channelType_isSlack() {
        assertThat(SlackNotificationChannel.CHANNEL_TYPE).isEqualTo("slack");
    }
}
