package io.quarkiverse.work.notifications.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkiverse.work.notifications.model.WorkItemNotificationRule;

/**
 * Unit tests for rule matching logic — no CDI, no DB.
 */
class NotificationDispatcherTest {

    @Test
    void matchesEventType_true_whenEventTypeInList() {
        final WorkItemNotificationRule rule = ruleFor("ASSIGNED,COMPLETED", null);
        assertThat(NotificationDispatcher.matches(rule, "ASSIGNED", null)).isTrue();
        assertThat(NotificationDispatcher.matches(rule, "COMPLETED", null)).isTrue();
    }

    @Test
    void matchesEventType_false_whenEventTypeNotInList() {
        final WorkItemNotificationRule rule = ruleFor("ASSIGNED", null);
        assertThat(NotificationDispatcher.matches(rule, "EXPIRED", null)).isFalse();
    }

    @Test
    void matchesCategory_true_whenRuleCategoryIsNull() {
        // null category = wildcard — matches any category
        final WorkItemNotificationRule rule = ruleFor("ASSIGNED", null);
        assertThat(NotificationDispatcher.matches(rule, "ASSIGNED", "loan")).isTrue();
        assertThat(NotificationDispatcher.matches(rule, "ASSIGNED", "legal")).isTrue();
        assertThat(NotificationDispatcher.matches(rule, "ASSIGNED", null)).isTrue();
    }

    @Test
    void matchesCategory_true_whenCategoryMatches() {
        final WorkItemNotificationRule rule = ruleFor("ASSIGNED", "loan-application");
        assertThat(NotificationDispatcher.matches(rule, "ASSIGNED", "loan-application")).isTrue();
    }

    @Test
    void matchesCategory_false_whenCategoryDiffers() {
        final WorkItemNotificationRule rule = ruleFor("ASSIGNED", "loan-application");
        assertThat(NotificationDispatcher.matches(rule, "ASSIGNED", "legal")).isFalse();
    }

    @Test
    void matchesCategory_false_whenWorkItemCategoryIsNullButRuleHasCategory() {
        final WorkItemNotificationRule rule = ruleFor("ASSIGNED", "loan-application");
        assertThat(NotificationDispatcher.matches(rule, "ASSIGNED", null)).isFalse();
    }

    @Test
    void disabledRule_neverMatches() {
        final WorkItemNotificationRule rule = ruleFor("ASSIGNED", null);
        rule.enabled = false;
        assertThat(NotificationDispatcher.matches(rule, "ASSIGNED", null)).isFalse();
    }

    @Test
    void filterMatchingRules_returnsOnlyMatches() {
        final WorkItemNotificationRule r1 = ruleFor("ASSIGNED", null);
        final WorkItemNotificationRule r2 = ruleFor("EXPIRED", "loan");
        final WorkItemNotificationRule r3 = ruleFor("ASSIGNED,COMPLETED", "loan");
        final List<WorkItemNotificationRule> rules = List.of(r1, r2, r3);

        final List<WorkItemNotificationRule> matched = NotificationDispatcher.filterMatching(rules, "ASSIGNED", "loan");
        assertThat(matched).containsExactlyInAnyOrder(r1, r3);
    }

    @Test
    void filterMatchingRules_emptyList_returnsEmpty() {
        assertThat(NotificationDispatcher.filterMatching(List.of(), "ASSIGNED", "loan"))
                .isEmpty();
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static WorkItemNotificationRule ruleFor(final String eventTypes, final String category) {
        final WorkItemNotificationRule rule = new WorkItemNotificationRule();
        rule.id = UUID.randomUUID();
        rule.channelType = "test";
        rule.targetUrl = "https://example.com/hook";
        rule.eventTypes = eventTypes;
        rule.category = category;
        rule.enabled = true;
        return rule;
    }
}
