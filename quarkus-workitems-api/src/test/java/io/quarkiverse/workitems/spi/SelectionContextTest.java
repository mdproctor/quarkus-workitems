package io.quarkiverse.workitems.spi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SelectionContextTest {

    @Test
    void constructor_setsAllFields() {
        final SelectionContext ctx = new SelectionContext(
                "finance", "HIGH", "audit,legal", "finance-team", "alice,bob");
        assertThat(ctx.category()).isEqualTo("finance");
        assertThat(ctx.priority()).isEqualTo("HIGH");
        assertThat(ctx.requiredCapabilities()).isEqualTo("audit,legal");
        assertThat(ctx.candidateGroups()).isEqualTo("finance-team");
        assertThat(ctx.candidateUsers()).isEqualTo("alice,bob");
    }

    @Test
    void constructor_acceptsNullFields() {
        final SelectionContext ctx = new SelectionContext(null, null, null, null, null);
        assertThat(ctx.category()).isNull();
        assertThat(ctx.candidateGroups()).isNull();
    }
}
