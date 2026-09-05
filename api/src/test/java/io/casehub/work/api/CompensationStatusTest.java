package io.casehub.work.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompensationStatusTest {

    @Test
    void enumValues() {
        assertEquals(3, CompensationStatus.values().length);
        assertNotNull(CompensationStatus.NONE);
        assertNotNull(CompensationStatus.COMPENSATING);
        assertNotNull(CompensationStatus.COMPENSATED);
    }
}
