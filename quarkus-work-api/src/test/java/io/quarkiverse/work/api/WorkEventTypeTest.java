package io.quarkiverse.work.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class WorkEventTypeTest {

    @Test
    void allExpectedValuesExist() {
        assertThat(WorkEventType.values()).extracting(Enum::name)
                .containsExactlyInAnyOrder(
                        "CREATED", "ASSIGNED", "STARTED", "COMPLETED", "REJECTED",
                        "DELEGATED", "RELEASED", "SUSPENDED", "RESUMED",
                        "CANCELLED", "EXPIRED", "CLAIM_EXPIRED", "ESCALATED");
    }

    @Test
    void concreteEvent_implementsAbstractMethods() {
        var event = new WorkLifecycleEvent() {
            @Override
            public WorkEventType eventType() {
                return WorkEventType.CREATED;
            }

            @Override
            public Map<String, Object> context() {
                return Map.of("id", "x");
            }

            @Override
            public Object source() {
                return "test-source";
            }
        };
        assertThat(event.eventType()).isEqualTo(WorkEventType.CREATED);
        assertThat(event.context()).containsKey("id");
        assertThat(event.source()).isEqualTo("test-source");
    }
}
