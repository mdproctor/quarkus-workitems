package io.quarkiverse.workitems.spi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AssignmentDecisionTest {

    @Test
    void noChange_hasAllNullFields() {
        final AssignmentDecision d = AssignmentDecision.noChange();
        assertThat(d.assigneeId()).isNull();
        assertThat(d.candidateGroups()).isNull();
        assertThat(d.candidateUsers()).isNull();
    }

    @Test
    void assignTo_setsAssigneeId_othersNull() {
        final AssignmentDecision d = AssignmentDecision.assignTo("alice");
        assertThat(d.assigneeId()).isEqualTo("alice");
        assertThat(d.candidateGroups()).isNull();
        assertThat(d.candidateUsers()).isNull();
    }

    @Test
    void narrowCandidates_setsGroupsAndUsers_assigneeNull() {
        final AssignmentDecision d = AssignmentDecision.narrowCandidates("finance-team", "alice,bob");
        assertThat(d.assigneeId()).isNull();
        assertThat(d.candidateGroups()).isEqualTo("finance-team");
        assertThat(d.candidateUsers()).isEqualTo("alice,bob");
    }

    @Test
    void isNoOp_trueWhenAllFieldsNull() {
        assertThat(AssignmentDecision.noChange().isNoOp()).isTrue();
        assertThat(AssignmentDecision.assignTo("alice").isNoOp()).isFalse();
        assertThat(AssignmentDecision.narrowCandidates("g", "u").isNoOp()).isFalse();
    }
}
