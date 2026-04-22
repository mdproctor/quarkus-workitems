package io.quarkiverse.workitems.runtime.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkiverse.workitems.runtime.model.WorkItem;
import io.quarkiverse.workitems.runtime.model.WorkItemPriority;
import io.quarkiverse.workitems.runtime.model.WorkItemStatus;

class WorkItemContextBuilderTest {

    @Test
    void toMap_containsId() {
        final WorkItem wi = new WorkItem();
        wi.id = UUID.randomUUID();
        wi.title = "Test";
        wi.status = WorkItemStatus.PENDING;
        wi.priority = WorkItemPriority.HIGH;
        final Map<String, Object> map = WorkItemContextBuilder.toMap(wi);
        assertThat(map).containsKey("id");
        assertThat(map.get("id")).isEqualTo(wi.id);
    }

    @Test
    void toMap_containsAllPublicNonStaticWorkItemFields() {
        final var expected = Arrays.stream(WorkItem.class.getFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(f -> f.getName())
                .toList();
        final WorkItem wi = new WorkItem();
        wi.id = UUID.randomUUID();
        final Map<String, Object> map = WorkItemContextBuilder.toMap(wi);
        assertThat(map.keySet()).containsAll(expected);
    }

    @Test
    void toMap_preservesEnumConstants() {
        final WorkItem wi = new WorkItem();
        wi.id = UUID.randomUUID();
        wi.status = WorkItemStatus.IN_PROGRESS;
        wi.priority = WorkItemPriority.CRITICAL;
        final Map<String, Object> map = WorkItemContextBuilder.toMap(wi);
        assertThat(map.get("status")).isEqualTo(WorkItemStatus.IN_PROGRESS);
        assertThat(map.get("priority")).isEqualTo(WorkItemPriority.CRITICAL);
    }
}
