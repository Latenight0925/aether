package dev.aether.modules.pest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PestTabSnapshotTest {
    @Test
    void parsesCountsCooldownAndOrderedPlots() {
        PestTabSnapshot snapshot = PestTabSnapshot.parse(List.of(
                "Alive: (7)",
                "Cooldown: (2m 5s)",
                "Plots: 12, 3, Plot 12"));

        assertEquals(7, snapshot.aliveCount());
        assertEquals(125, snapshot.cooldownSeconds());
        assertEquals(Set.of("12", "3"), snapshot.infestedPlots());
    }

    @Test
    void missingBonusLineRemainsUnknown() {
        assertNull(PestTabSnapshot.parse(List.of("Alive: 2")).bonusInactive());
    }

    @Test
    void maxPestsCooldownKeepsAliveCount() {
        PestTabSnapshot snapshot =
                PestTabSnapshot.parse(List.of("Alive: 8", "Cooldown: MAX PESTS"));
        assertEquals(8, snapshot.aliveCount());
        assertEquals(999, snapshot.cooldownSeconds());
    }
}
