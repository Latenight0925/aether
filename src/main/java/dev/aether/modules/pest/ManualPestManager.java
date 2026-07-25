package dev.aether.modules.pest;

import dev.aether.bootstrap.AetherKeybindHandler;
import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.failsafe.DesktopNotificationManager;
import dev.aether.modules.failsafe.FailsafeAction;
import dev.aether.modules.failsafe.FailsafeColourFlashManager;
import dev.aether.modules.failsafe.FailsafeSoundManager;
import dev.aether.modules.failsafe.FailsafeWindowFocusManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.modules.pest.helpers.PestPrepSwapManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.CommandUtils;
import net.minecraft.client.Minecraft;

/**
 * Hands pest control back to the player. When pests are detected while farming
 * it tabs the window in (failsafe-style), stops the macro, and waits for the
 * player to clear the pests by hand. Once the tab list reports no pests left it
 * warps to garden and restarts the farming macro.
 *
 * <p>Runs independently of the automated {@link PestManager} pipeline, which is
 * short-circuited while {@link AetherConfig#MANUAL_PEST_MODE} is on.
 */
public final class ManualPestManager {

    private enum Phase { IDLE, WAITING, RESUMING }

    private static volatile Phase phase = Phase.IDLE;
    private static volatile long clearedSinceMs = 0L;
    private static volatile long cooldownUntilMs = 0L;
    /** Loadout slot we were on when we paused, captured before stopMacro wipes tracking. */
    private static volatile int pausedFromLoadout = -1;

    private ManualPestManager() {
    }

    public static void reset() {
        phase = Phase.IDLE;
        clearedSinceMs = 0L;
        cooldownUntilMs = 0L;
        pausedFromLoadout = -1;
    }

    public static boolean isActive() {
        return phase != Phase.IDLE;
    }

    public static void update() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.getConnection() == null) {
            return;
        }
        if (!AetherConfig.MANUAL_PEST_MODE.get()) {
            if (phase != Phase.IDLE) {
                reset();
            }
            return;
        }

        switch (phase) {
            case IDLE -> tickIdle(client);
            case WAITING -> tickWaiting(client);
            case RESUMING -> { /* resume worker owns the transition back to IDLE */ }
        }
    }

    private static void tickIdle(Minecraft client) {
        if (System.currentTimeMillis() < cooldownUntilMs) {
            return;
        }
        if (MacroStateManager.getCurrentState() != MacroState.State.FARMING) {
            return;
        }
        if (client.screen != null) {
            return;
        }
        // Don't cut in on the pre-spawn prep loadout swap; wait for it to finish so we
        // pause from a known loadout instead of mid-wardrobe.
        if (LoadoutManager.isSwappingLoadout || PestPrepSwapManager.isPrepSwapping) {
            return;
        }

        int count = currentPestCount(client);
        if (count >= Math.max(1, AetherConfig.MANUAL_PEST_THRESHOLD.get())) {
            beginPause(client, count);
        }
    }

    private static void tickWaiting(Minecraft client) {
        // If the macro is running again it was restarted outside our resume flow
        // (e.g. the player toggled it back on), so drop the manual pause.
        if (MacroStateManager.isMacroRunning()) {
            reset();
            return;
        }

        int count = currentPestCount(client);
        long now = System.currentTimeMillis();
        if (count <= 0) {
            if (clearedSinceMs == 0L) {
                clearedSinceMs = now;
            } else if (now - clearedSinceMs >= AetherConfig.MANUAL_PEST_CONFIRM_DELAY_MS.get()) {
                beginResume(client);
            }
        } else {
            clearedSinceMs = 0L;
        }
    }

    private static void beginPause(Minecraft client, int count) {
        phase = Phase.WAITING;
        clearedSinceMs = 0L;
        // Capture the loadout we're on now (e.g. the prep loadout after a 1->2 swap)
        // before stopMacro resets loadout tracking to -1.
        int previousLoadout = LoadoutManager.trackedLoadoutSlot;
        pausedFromLoadout = previousLoadout;
        ClientUtils.sendMessage("§eManual Pest Mode: §f" + count
                + " pest(s) detected. §ePausing for manual kill.", false);
        // Stop first so the macro stops farming and releases keys, then run the
        // (blocking) loadout swap off the tick thread. Tab the player in only once
        // the swap is done so they land ready to fight rather than mid-wardrobe GUI.
        MacroStateManager.stopMacro(client, "Manual pest mode: pausing for manual pest kill", false);
        // Setspawn at the farming spot so the resume /warp garden drops us right back
        // where we paused instead of the garden hub.
        if (AetherConfig.MANUAL_PEST_SET_SPAWN.get()) {
            CommandUtils.initiateSetSpawn();
        }
        MacroWorkerThread.getInstance().submit("ManualPest-Prep", () -> {
            swapToPestKillLoadout(client, previousLoadout);
            client.execute(() -> fireAlert(count));
        });
    }

    /**
     * Swap to the pest-kill loadout ("Pest Kill Loadout Slot", {@code LOADOUT_SLOT_PEST_KILL})
     * on pause so the player fights with the right gear. This is the slot the automated
     * cleaner uses to kill, and it commonly equals the farming slot (you farm and kill on
     * the same loadout) - so the swap is decided against the loadout we were actually on
     * (typically the pre-spawn prep loadout), not against the farming slot.
     */
    private static void swapToPestKillLoadout(Minecraft client, int previousLoadout) {
        if (!AetherConfig.MANUAL_PEST_SWAP_LOADOUT.get()) {
            return;
        }
        int targetSlot = AetherConfig.LOADOUT_SLOT_PEST_KILL.get();
        if (targetSlot <= 0) {
            return;
        }
        // Unknown tracking (-1) means we never swapped away, so we're on the farming loadout.
        int effectivePrevious = previousLoadout > 0 ? previousLoadout : AetherConfig.LOADOUT_SLOT_FARMING.get();
        if (effectivePrevious == targetSlot) {
            ClientUtils.sendDebugMessage("Manual pest: already on kill loadout " + targetSlot + ", no swap needed.");
            return;
        }
        swapLoadout(client, targetSlot);
    }

    /**
     * Swap back to the farming loadout on resume so farming never resumes on the
     * pest-kill (or leftover prep) loadout. Runs regardless of the swap toggle - even
     * if the pause swap was skipped, a separate prep swap may have moved us - but is a
     * no-op when we're already on the farming loadout, so users on a single loadout
     * never see a pointless wardrobe open.
     */
    private static void restoreFarmingLoadout(Minecraft client) {
        int targetSlot = AetherConfig.LOADOUT_SLOT_FARMING.get();
        if (targetSlot <= 0) {
            return;
        }
        // After a mod-driven swap tracking is reliable; otherwise fall back to whatever
        // loadout we captured at pause time (tracking is wiped by stopMacro).
        int current = LoadoutManager.trackedLoadoutSlot > 0 ? LoadoutManager.trackedLoadoutSlot : pausedFromLoadout;
        if (current <= 0 || current == targetSlot) {
            return;
        }
        swapLoadout(client, targetSlot);
    }

    /**
     * Blocking wardrobe swap, modelled on
     * {@code PestReturnManager.restoreFarmingLoadout}. The wardrobe GUI is driven by
     * the tick handler's loadout menu handler, which runs regardless of macro state,
     * so this still completes while the macro is paused.
     */
    private static void swapLoadout(Minecraft client, int targetSlot) {
        if (LoadoutManager.trackedLoadoutSlot == targetSlot) {
            return;
        }

        ClientUtils.sendDebugMessage("Manual pest: swapping to loadout slot " + targetSlot);
        client.execute(() -> GearManager.ensureLoadoutSlot(client, targetSlot));

        long startWait = System.currentTimeMillis();
        while (!LoadoutManager.isSwappingLoadout && System.currentTimeMillis() - startWait < 2000) {
            MacroWorkerThread.sleep(25);
        }

        ClientUtils.waitForWardrobeGui();
        long finishWait = System.currentTimeMillis();
        while (LoadoutManager.isSwappingLoadout && System.currentTimeMillis() - finishWait < 7000) {
            MacroWorkerThread.sleep(50);
        }
        if (LoadoutManager.isSwappingLoadout) {
            LoadoutManager.forceLoadoutCompletionFailsafe(client);
        }

        long cleanupWait = System.currentTimeMillis();
        while (LoadoutManager.loadoutCleanupTicks > 0 && System.currentTimeMillis() - cleanupWait < 2000) {
            MacroWorkerThread.sleep(50);
        }
        MacroWorkerThread.sleep(250);
    }

    private static void beginResume(Minecraft client) {
        phase = Phase.RESUMING;
        ClientUtils.sendMessage("§aManual Pest Mode: pests cleared. Warping to garden and restarting the macro.",
                false);
        MacroWorkerThread.getInstance().submit("ManualPest-Resume", () -> {
            try {
                CommandUtils.warpGarden();
                MacroWorkerThread.sleep(AetherConfig.MANUAL_PEST_RESTART_DELAY_MS.get());
                // Swap back to farming gear before the macro restarts, otherwise it
                // resumes farming still wearing the pest-kill loadout (the desync).
                restoreFarmingLoadout(client);
            } finally {
                cooldownUntilMs = System.currentTimeMillis()
                        + AetherConfig.MANUAL_PEST_COOLDOWN_SECONDS.get() * 1000L;
                client.execute(() -> {
                    if (AetherConfig.MANUAL_PEST_MODE.get()) {
                        AetherKeybindHandler.startFarmingMacro(client, false);
                    }
                    phase = Phase.IDLE;
                    clearedSinceMs = 0L;
                });
            }
        });
    }

    private static void fireAlert(int count) {
        FailsafeColourFlashManager.trigger();
        FailsafeSoundManager.playConfiguredSound(FailsafeAction.STOP);
        if (AetherConfig.MANUAL_PEST_TAB_IN.get()) {
            FailsafeWindowFocusManager.focusWindowNow();
        }
        if (AetherConfig.FAILSAFE_DESKTOP_NOTIFICATION_ENABLED.get()) {
            DesktopNotificationManager.notify("Pests Detected",
                    count + " pest(s) spawned — macro paused for manual kill.", true);
        }
    }

    /**
     * Best-effort current pest count. Trusts the explicit tab count when present
     * (0 or positive); when the pests line is absent, falls back to the infested
     * plot list so clearing is detected even if the count line disappears.
     */
    private static int currentPestCount(Minecraft client) {
        int rawTab = PestManager.getTabAliveCountNow(client);
        if (rawTab >= 0) {
            return rawTab;
        }
        return PestManager.getInfestedPlotsFromTab(client).isEmpty() ? 0 : 1;
    }
}