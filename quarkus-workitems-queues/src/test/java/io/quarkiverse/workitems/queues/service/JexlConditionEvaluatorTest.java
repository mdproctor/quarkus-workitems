package io.quarkiverse.workitems.queues.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.workitems.runtime.model.LabelPersistence;
import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemLabel;
import io.quarkiverse.workitems.runtime.model.WorkItemPriority;
import io.quarkiverse.workitems.runtime.model.WorkItemStatus;

class JexlConditionEvaluatorTest {
    private JexlConditionEvaluator evaluator;

    @BeforeEach
    void setup() {
        evaluator = new JexlConditionEvaluator();
    }

    @Test
    void language_isJexl() {
        assertThat(evaluator.language()).isEqualTo("jexl");
    }

    @Test
    void evaluate_priorityHigh_matchesHigh() {
        var wi = wi(WorkItemStatus.PENDING, WorkItemPriority.HIGH, null);
        assertThat(evaluator.evaluate(wi, "priority == 'HIGH'")).isTrue();
    }

    @Test
    void evaluate_priorityHigh_doesNotMatchNormal() {
        var wi = wi(WorkItemStatus.PENDING, WorkItemPriority.NORMAL, null);
        assertThat(evaluator.evaluate(wi, "priority == 'HIGH'")).isFalse();
    }

    @Test
    void evaluate_assigneeNull_matchesNull() {
        var wi = wi(WorkItemStatus.PENDING, WorkItemPriority.NORMAL, null);
        assertThat(evaluator.evaluate(wi, "assigneeId == null")).isTrue();
    }

    @Test
    void evaluate_statusPending() {
        var wi = wi(WorkItemStatus.PENDING, WorkItemPriority.NORMAL, null);
        assertThat(evaluator.evaluate(wi, "status == 'PENDING'")).isTrue();
    }

    @Test
    void evaluate_andCondition() {
        var wi = wi(WorkItemStatus.PENDING, WorkItemPriority.HIGH, null);
        assertThat(evaluator.evaluate(wi, "priority == 'HIGH' && status == 'PENDING'")).isTrue();
    }

    @Test
    void evaluate_malformedExpression_returnsFalse() {
        var wi = wi(WorkItemStatus.PENDING, WorkItemPriority.NORMAL, null);
        assertThat(evaluator.evaluate(wi, "this is not jexl @@##")).isFalse();
    }

    @Test
    void evaluate_category() {
        var wi = wi(WorkItemStatus.PENDING, WorkItemPriority.NORMAL, null);
        wi.category = "legal";
        assertThat(evaluator.evaluate(wi, "category == 'legal'")).isTrue();
    }

    @Test
    void evaluate_labelContains_matchesWorkItemWithLabel() {
        var wi = wi(WorkItemStatus.PENDING, WorkItemPriority.NORMAL, null);
        wi.labels.add(new WorkItemLabel("legal/contracts", LabelPersistence.MANUAL, "alice"));
        assertThat(evaluator.evaluate(wi, "labels.contains('legal/contracts')")).isTrue();
    }

    @Test
    void evaluate_labelNotContains_noMatch() {
        var wi = wi(WorkItemStatus.PENDING, WorkItemPriority.NORMAL, null);
        assertThat(evaluator.evaluate(wi, "labels.contains('legal/contracts')")).isFalse();
    }

    private WorkItem wi(final WorkItemStatus s, final WorkItemPriority p, final String assigneeId) {
        var wi = new WorkItem();
        wi.status = s;
        wi.priority = p;
        wi.assigneeId = assigneeId;
        return wi;
    }
}
