package dev.aether.modules.pest;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.failsafe.DesktopNotificationManager;
import dev.aether.modules.failsafe.FailsafeColourFlashManager;
import dev.aether.modules.failsafe.FailsafeSoundManager;
import dev.aether.modules.farming.UngrabMouse;
import dev.aether.modules.performance.MuteManager;
import dev.aether.modules.performance.PerformanceModeManager;
import dev.aether.modules.pest.helpers.PestCompletionGuard;
import dev.aether.modules.pest.helpers.PestDiscoDestinationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.WindowFocusHelper;
import net.minecraft.client.Minecraft;

import java.util.Set;

/**
 * Manual implementation of the pest lifecycle's CLEANING stage. The shared
 * PRE and POST stages remain responsible for travel, loadouts, traps, visitors,
 * and returning to farming.
 */
public final class ManualPestManager {

    private enum Phase { IDLE, WAITING }

    private static volatile Phase phase = Phase.IDLE;
    private static volatile long waitingStartedAt = 0L;
    private static volatile int clearedPestTabTicks = 0;

    private ManualPestManager() {
    }

    public static void reset() {
        phase = Phase.IDLE;
        waitingStartedAt = 0L;
        clearedPestTabTicks = 0;
        PestManager.clearCleaningTriggerPending();
    }

    public static boolean isActive() {
        return phase != Phase.IDLE;
    }

    public static void requestEarlyFinish(Minecraft client) {
        if (client == null
                || phase != Phase.WAITING
                || !AetherConfig.MANUAL_PEST_MODE.get()
                || MacroStateManager.getCurrentState() != MacroState.State.CLEANING
                || !PestManager.isCleaningInProgress()) {
            return;
        }

        ClientUtils.sendMessage("\u00A7eManual Pest Mode: early finish requested. Running pest post-actions.", false);
        finishCleaningStage(client);
    }

    public static boolean startCleaningStage(Minecraft client, int count) {
        if (!AetherConfig.MANUAL_PEST_MODE.get() || phase != Phase.IDLE) {
            return false;
        }
        if (client == null || client.player == null || client.getConnection() == null) {
            return false;
        }
        if (!MacroStateManager.isMacroRunning()) {
            return false;
        }

        beginWaiting(client, Math.max(1, count));
        return true;
    }

    public static void update() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.getConnection() == null) {
            return;
        }
        if (!AetherConfig.MANUAL_PEST_MODE.get()) {
            if (phase != Phase.IDLE) {
                phase = Phase.IDLE;
                waitingStartedAt = 0L;
                clearedPestTabTicks = 0;
                PestManager.handlePestCleaningFinished(client);
            }
            return;
        }

        if (phase == Phase.WAITING) {
            tickWaiting(client);
        }
    }

    private static void tickWaiting(Minecraft client) {
        if (!MacroStateManager.isMacroRunning()
                || MacroStateManager.getCurrentState() != MacroState.State.CLEANING
                || !PestManager.isCleaningInProgress()) {
            reset();
            return;
        }

        if (shouldFinishForAliveCount(client, currentPestCount(client))) {
            if (!PestCompletionGuard.shouldAcceptFinishReading(waitingStartedAt, false)) {
                if (clearedPestTabTicks == 0) {
                    ClientUtils.sendDebugMessage("Manual pest: ignoring clear tab reading during startup grace.");
                }
                clearedPestTabTicks = 0;
                return;
            }

            clearedPestTabTicks++;
            if (PestCompletionGuard.isConfirmed(clearedPestTabTicks)) {
                finishCleaningStage(client);
            }
        } else {
            clearedPestTabTicks = 0;
        }
    }

    private static void beginWaiting(Minecraft client, int count) {
        ClientUtils.sendMessage("\u00A7eManual Pest Mode: \u00A7f" + count
                + " pest(s) detected. \u00A7ePausing for manual kill.", false);
        ClientUtils.forceReleaseKeys();
        client.execute(() -> {
            UngrabMouse.clearMacroUngrab();
            PerformanceModeManager.stop(client);
            MuteManager.stop(client);
        });
        phase = Phase.WAITING;
        waitingStartedAt = System.currentTimeMillis();
        clearedPestTabTicks = 0;
        fireAlert(count);
    }

    private static void finishCleaningStage(Minecraft client) {
        phase = Phase.IDLE;
        waitingStartedAt = 0L;
        clearedPestTabTicks = 0;
        ClientUtils.sendMessage("\u00A7aManual Pest Mode: pests cleared. Running pest post-actions.", false);
        PestManager.handlePestCleaningFinished(client);
    }

    private static void fireAlert(int count) {
        FailsafeColourFlashManager.trigger();
        FailsafeSoundManager.playConfiguredSoundFile(AetherConfig.MANUAL_PEST_SOUND_FILE.get());
        WindowFocusHelper.focusMinecraftWindow("ManualPestWindowFocus");
        if (AetherConfig.FAILSAFE_DESKTOP_NOTIFICATION_ENABLED.get()) {
            DesktopNotificationManager.notify("Pests Detected",
                    count + " pest(s) spawned - macro paused for manual kill.", true);
        }
    }

    private static int currentPestCount(Minecraft client) {
        int rawTab = PestManager.getTabAliveCountNow(client);
        if (rawTab >= 0) {
            return rawTab;
        }
        return PestManager.getInfestedPlotsFromTab(client).isEmpty() ? 0 : 1;
    }

    private static boolean shouldFinishForAliveCount(Minecraft client, int aliveCount) {
        if (aliveCount <= 0) {
            return true;
        }
        if (!AetherConfig.LEAVE_ONE_PEST_ALIVE.get()) {
            return false;
        }

        Set<String> infested = PestManager.getInfestedPlotsFromTab(client);
        if (infested.isEmpty()) {
            return isLeaveOneWhitelistedPlot(PestManager.currentInfestedPlot) && aliveCount <= 1;
        }

        int whitelistedPlots = 0;
        for (String plot : infested) {
            if (!isLeaveOneWhitelistedPlot(plot)) {
                return false;
            }
            whitelistedPlots++;
        }
        return aliveCount <= whitelistedPlots;
    }

    private static boolean isLeaveOneWhitelistedPlot(String plot) {
        String normalizedPlot = PestDiscoDestinationManager.normalizePlot(plot);
        if (normalizedPlot.isEmpty() || normalizedPlot.equalsIgnoreCase("unknown")) {
            return false;
        }

        for (String configuredPlot : AetherConfig.LEAVE_ONE_PEST_PLOTS.get()) {
            if (PestDiscoDestinationManager.normalizePlot(configuredPlot).equals(normalizedPlot)) {
                return true;
            }
        }
        return false;
    }
}
