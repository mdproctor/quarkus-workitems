package io.casehub.work.examples.loanrollback;

import java.util.List;

import io.casehub.work.examples.StepLog;

public record LoanRollbackResponse(
        String scenario,
        List<StepLog> steps,
        List<LoanStepSummary> forwardSteps,
        List<LoanStepSummary> compensationSteps,
        String compensationOrder) {
}
