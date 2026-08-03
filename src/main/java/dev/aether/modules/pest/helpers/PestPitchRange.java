package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;

record PestPitchRange(float minimum, float maximum) {
    static PestPitchRange configured() {
        float first = AetherConfig.PEST_ABOVE_TARGET_PITCH_MIN.get();
        float second = AetherConfig.PEST_ABOVE_TARGET_PITCH_MAX.get();
        return new PestPitchRange(Math.min(first, second), Math.max(first, second));
    }

    boolean contains(float pitch) {
        return pitch >= minimum && pitch <= maximum;
    }

    float clamp(float pitch) {
        return Math.max(minimum, Math.min(maximum, pitch));
    }

    float bucketFor(int seed) {
        float width = maximum - minimum;
        int bucket = Math.floorMod(seed, (int) width + 1);
        return maximum - bucket;
    }
}
