package io.quarkiverse.workitems.runtime.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import io.quarkiverse.work.api.EscalationPolicy;
import io.quarkiverse.workitems.runtime.config.WorkItemsConfig;

@ApplicationScoped
public class EscalationPolicyProducer {

    @Inject
    WorkItemsConfig config;

    @Inject
    NotifyEscalationPolicy notifyPolicy;

    @Inject
    AutoRejectEscalationPolicy autoRejectPolicy;

    @Inject
    ReassignEscalationPolicy reassignPolicy;

    @Produces
    @ApplicationScoped
    @ExpiryEscalation
    public EscalationPolicy expiryPolicy() {
        return switch (config.escalationPolicy()) {
            case "notify" -> notifyPolicy;
            case "auto-reject" -> autoRejectPolicy;
            case "reassign" -> reassignPolicy;
            default -> throw new IllegalArgumentException(
                    "Unknown escalation-policy: " + config.escalationPolicy()
                            + ". Valid values: notify, auto-reject, reassign");
        };
    }

    @Produces
    @ApplicationScoped
    @ClaimEscalation
    public EscalationPolicy claimPolicy() {
        return switch (config.claimEscalationPolicy()) {
            case "notify" -> notifyPolicy;
            case "reassign" -> reassignPolicy;
            default -> throw new IllegalArgumentException(
                    "Unknown claim-escalation-policy: " + config.claimEscalationPolicy()
                            + ". Valid values: notify, reassign");
        };
    }
}
