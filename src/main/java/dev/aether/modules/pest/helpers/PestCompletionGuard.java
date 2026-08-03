package dev.aether.modules.pest.helpers;

/**
 * Shared guard for pest completion decisions that are driven by tab-list data.
 * The tab list can lag immediately after a pest spawn/cleaning handoff, so
 * callers should ignore finish-level readings during startup and then require
 * consecutive confirmations before treating pests as cleared.
 */
public final class PestCompletionGuard {
    public static final long STARTUP_FINISH_GRACE_MS = 5_000L;
    public static final int TAB_FINISH_CONFIRM_TICKS = 10;

    private PestCompletionGuard() {
    }

    public static boolean isInStartupGrace(long activatedAtMs) {
        return isInStartupGrace(activatedAtMs, System.currentTimeMillis());
    }

    static boolean isInStartupGrace(long activatedAtMs, long nowMs) {
        return nowMs - activatedAtMs < STARTUP_FINISH_GRACE_MS;
    }

    public static boolean isStartupGraceElapsed(long activatedAtMs) {
        return !isInStartupGrace(activatedAtMs);
    }

    public static boolean shouldAcceptFinishReading(long activatedAtMs, boolean allowDuringStartup) {
        return allowDuringStartup || isStartupGraceElapsed(activatedAtMs);
    }

    public static boolean isConfirmed(int finishReadingTicks) {
        return finishReadingTicks >= TAB_FINISH_CONFIRM_TICKS;
    }
}
