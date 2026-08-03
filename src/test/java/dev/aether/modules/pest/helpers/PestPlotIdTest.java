package dev.aether.modules.pest.helpers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PestPlotIdTest {
    @Test
    void normalizesDecoratedPlotNames() {
        assertEquals("12", PestPlotId.normalize(" Plot #12 "));
        assertTrue(PestPlotId.equals("Plot 7", "7"));
    }

    @Test
    void rejectsMissingPlotIdentifiers() {
        assertFalse(PestPlotId.isUsable(null));
        assertFalse(PestPlotId.isUsable("0"));
        assertFalse(PestPlotId.isUsable("Unknown"));
        assertTrue(PestPlotId.isUsable("5"));
    }
}
