package io.quarkiverse.tarkus.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class TarkusConfigDefaultsTest {

    @Inject
    TarkusConfig config;

    @Test
    void defaultExpiryHoursIs24() {
        assertThat(config.defaultExpiryHours()).isEqualTo(24);
    }

    @Test
    void defaultClaimHoursIs4() {
        assertThat(config.defaultClaimHours()).isEqualTo(4);
    }

    @Test
    void escalationPolicyIsNotify() {
        assertThat(config.escalationPolicy()).isEqualTo("notify");
    }

    @Test
    void claimEscalationPolicyIsNotify() {
        assertThat(config.claimEscalationPolicy()).isEqualTo("notify");
    }

    @Test
    void cleanupExpiryCheckSecondsIs60() {
        assertThat(config.cleanup().expiryCheckSeconds()).isEqualTo(60);
    }
}
