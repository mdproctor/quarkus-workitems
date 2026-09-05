package io.casehub.work.examples.loanrollback;

import java.util.UUID;

public record LoanStepSummary(
        String callerRef,
        UUID originalId,
        UUID compensatingId,
        String compensationStatus) {
}
