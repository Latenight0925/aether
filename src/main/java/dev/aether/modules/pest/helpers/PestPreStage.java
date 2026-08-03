package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.CommandUtils;
import net.minecraft.client.Minecraft;

/**
 * Shared preparation for automatic and manual pest cleaning.
 */
final class PestPreStage {

    private PestPreStage() {
    }

    static boolean run(Minecraft client, String plot, int sessionId) throws InterruptedException {
        if (shouldAbort(client, sessionId)) {
            return false;
        }

        if (!CommandUtils.setSpawn()) {
            ClientUtils.sendMessage("\u00A7c[Aether] /setspawn timed out - aborting pest cleaning to prevent roof spawn.",
                    false);
            return false;
        }

        if (AetherConfig.SUNSET_PESTS.get()) {
            if (!PestLifecycleManager.prepareSunsetPestsDaytime(client)) {
                return false;
            }
        }

        if (!swapToPestLoadout(client, sessionId)) {
            return false;
        }

        PestPrepSwapManager.clearCycleState();
        if (!moveToTargetPlot(client, plot, sessionId)) {
            return false;
        }

        return moveToRoofIfNeeded(client, plot, sessionId);
    }

    private static boolean moveToTargetPlot(Minecraft client, String plot, int sessionId) {
        if (!PestPlotId.isUsable(plot)) {
            return !shouldAbort(client, sessionId);
        }

        String currentPlot = ClientUtils.getCurrentPlot();
        boolean scoreboardMatch = currentPlot != null && currentPlot.equalsIgnoreCase(plot);
        String freshChatPlot = CommandUtils.getFreshKnownPlotChat();
        boolean chatMatch = freshChatPlot != null && freshChatPlot.equalsIgnoreCase(plot);
        boolean alreadyOnPlot = scoreboardMatch;
        if (!alreadyOnPlot && (currentPlot == null || currentPlot.equalsIgnoreCase("Unknown"))) {
            alreadyOnPlot = chatMatch;
        }

        boolean forcePlotTp =
                alreadyOnPlot && AetherConfig.PEST_PLOT_TP_FOR_CURRENT_PLOT.get();

        if (alreadyOnPlot && !forcePlotTp) {
            String source = chatMatch ? "chat" : "scoreboard";
            ClientUtils.sendDebugMessage("Already on plot " + plot + " (via " + source + "), skipping plottp.");
            return !shouldAbort(client, sessionId);
        }

        ClientUtils.sendDebugMessage((forcePlotTp ? "Forcing" : "Running")
                + " plottp to " + plot + " during pest PRE stage.");
        CommandUtils.plotTp(plot);
        MacroWorkerThread.sleep(200);
        return !shouldAbort(client, sessionId);
    }

    private static boolean moveToRoofIfNeeded(Minecraft client, String plot, int sessionId)
            throws InterruptedException {
        if (!PestAotvManager.shouldDoAotvOnCurrentPlot(client, plot, true)
                || !PestAotvManager.hasRoofAbove(client)) {
            return !shouldAbort(client, sessionId);
        }

        ClientUtils.sendDebugMessage("Pest PRE stage: using AOTV to reach the roof on plot " + plot + ".");
        PestAotvManager.startPreparationAotv(client);
        while (PestAotvManager.isPreparationAotvActive()) {
            if (shouldAbort(client, sessionId)) {
                PestAotvManager.cancelPreparationAotv(client);
                return false;
            }
            MacroWorkerThread.sleep(25);
        }

        if (PestAotvManager.consumePreparationPlotRecovery()) {
            ClientUtils.sendDebugMessage("Pest PRE stage: AOTV timed out; using plottp to recover.");
            CommandUtils.initiatePlotTp(plot);
            MacroWorkerThread.sleep(2500);
        } else {
            PestAotvManager.rotateDownAfterAotv(client);
        }
        return !shouldAbort(client, sessionId);
    }

    private static boolean swapToPestLoadout(Minecraft client, int sessionId) throws InterruptedException {
        int targetSlot = AetherConfig.LOADOUT_SLOT_PEST_KILL.get();
        if (targetSlot <= 0 || LoadoutManager.trackedLoadoutSlot == targetSlot) {
            return !shouldAbort(client, sessionId);
        }

        ClientUtils.sendMessage("\u00A7eSwapping to pest kill loadout (slot " + targetSlot + ")...", true);
        client.execute(() -> GearManager.ensureLoadoutSlot(client, targetSlot));

        long startWait = System.currentTimeMillis();
        while (!LoadoutManager.isSwappingLoadout && System.currentTimeMillis() - startWait < 2000) {
            if (shouldAbort(client, sessionId)) {
                return false;
            }
            MacroWorkerThread.sleep(25);
        }

        ClientUtils.waitForWardrobeGui();
        long finishWait = System.currentTimeMillis();
        while (LoadoutManager.isSwappingLoadout && System.currentTimeMillis() - finishWait < 7000) {
            MacroWorkerThread.sleep(50);
        }
        if (LoadoutManager.isSwappingLoadout) {
            ClientUtils.sendDebugMessage("Loadout swap timed out in pest PRE stage; triggering completion failsafe.");
            LoadoutManager.forceLoadoutCompletionFailsafe(client);
        }

        while (LoadoutManager.loadoutCleanupTicks > 0) {
            MacroWorkerThread.sleep(50);
        }
        MacroWorkerThread.sleep(250);
        return !shouldAbort(client, sessionId);
    }

    private static boolean shouldAbort(Minecraft client, int sessionId) {
        return MacroWorkerThread.shouldAbortTask(client)
                || sessionId != PestManager.getCurrentPestSessionId()
                || !PestManager.isCleaningInProgress();
    }
}
