package io.casehub.work.engine;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseCompensationEventTest {

    @Test
    void type_returnsCorrectPrefix_forStarted() {
        var event = new CaseCompensationEvent(
                CaseCompensationEvent.Kind.STARTED, "tenant-1",
                UUID.randomUUID(), "ClinicalTrial", "COMPENSATING", "operator-1");
        assertThat(event.type()).isEqualTo("io.casehub.engine.case.compensation.started");
    }

    @Test
    void type_returnsCorrectPrefix_forCompleted() {
        var event = new CaseCompensationEvent(
                CaseCompensationEvent.Kind.COMPLETED, "tenant-1",
                UUID.randomUUID(), "ClinicalTrial", "COMPENSATED", "operator-1");
        assertThat(event.type()).isEqualTo("io.casehub.engine.case.compensation.completed");
    }

    @Test
    void type_returnsCorrectPrefix_forFaulted() {
        var event = new CaseCompensationEvent(
                CaseCompensationEvent.Kind.FAULTED, "tenant-1",
                UUID.randomUUID(), "ClinicalTrial", "COMPENSATION_FAULTED", "operator-1");
        assertThat(event.type()).isEqualTo("io.casehub.engine.case.compensation.faulted");
    }

    @Test
    void tenancyId_returnsConstructionValue() {
        var event = new CaseCompensationEvent(
                CaseCompensationEvent.Kind.STARTED, "my-tenant",
                UUID.randomUUID(), null, "COMPENSATING", null);
        assertThat(event.tenancyId()).isEqualTo("my-tenant");
    }

    @Test
    void rejectsNullKind() {
        assertThatThrownBy(() -> new CaseCompensationEvent(
                null, "t", UUID.randomUUID(), null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullTenancyId() {
        assertThatThrownBy(() -> new CaseCompensationEvent(
                CaseCompensationEvent.Kind.STARTED, null,
                UUID.randomUUID(), null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullCaseId() {
        assertThatThrownBy(() -> new CaseCompensationEvent(
                CaseCompensationEvent.Kind.STARTED, "t",
                null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
