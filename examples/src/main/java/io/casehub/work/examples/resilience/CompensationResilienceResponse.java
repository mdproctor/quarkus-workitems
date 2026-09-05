package io.casehub.work.examples.resilience;

import java.util.List;

import io.casehub.work.examples.StepLog;

public record CompensationResilienceResponse(
        String scenario,
        List<StepLog> steps,
        GuardResult nonCompletedGuard,
        GuardResult doubleCompensationGuard,
        GuardResult compensatorGuard,
        LifecycleResult suspendResumeLifecycle) {
}
