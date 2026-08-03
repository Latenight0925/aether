package dev.aether.modules.pest.helpers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PestDestroyerRuntimeTest {
    @Test
    void beginRunClearsTransientAndNavigationState() {
        PestDestroyerRuntime runtime = new PestDestroyerRuntime();
        runtime.stuckTicks = 12;
        runtime.aotvSlot = 4;
        runtime.navigation.plotQueue.add("3");

        runtime.beginRun(6, 1234L);

        assertTrue(runtime.active);
        assertEquals(PestDestroyer.State.IDLE, runtime.state);
        assertEquals(6, runtime.vacuumSlot);
        assertEquals(1234L, runtime.activatedAt);
        assertEquals(0, runtime.stuckTicks);
        assertEquals(-1, runtime.aotvSlot);
        assertTrue(runtime.navigation.plotQueue.isEmpty());
    }

    @Test
    void resetAllReturnsRuntimeToInactiveBaseline() {
        PestDestroyerRuntime runtime = new PestDestroyerRuntime();
        runtime.beginRun(2, 10L);
        runtime.state = PestDestroyer.State.KILL_PEST;
        runtime.zeroPestTabTicks = 3;
        runtime.navigation.plotQueue.add("8");

        runtime.resetAll();

        assertFalse(runtime.active);
        assertEquals(PestDestroyer.State.IDLE, runtime.state);
        assertEquals(0, runtime.zeroPestTabTicks);
        assertTrue(runtime.navigation.plotQueue.isEmpty());
    }
}
