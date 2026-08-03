package dev.aether.modules.pest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynamicPestPolicyTest {
    @Test
    void contestWinsCombinedMode() {
        assertEquals(
                "Wheat",
                DynamicPestPolicy.resolve(
                        2,
                        true,
                        List.of("Carrot"),
                        List.of("Carrot"),
                        List.of("Wheat"),
                        List.of("Wheat")));
    }

    @Test
    void normalizesSpecialCropNames() {
        assertEquals("Melon Slice", DynamicPestPolicy.normalizeCrop("Melon"));
        assertEquals("Mushrooms", DynamicPestPolicy.normalizeCrop("Mushroom"));
    }

    @Test
    void parsesUnicodeContestMarkers() {
        assertEquals(
                List.of("Wheat", "Mushrooms"),
                DynamicPestPolicy.parseContestCrops(
                        List.of("\u25CB Wheat", "\u2618 Mushroom", "unrelated")));
    }
}
