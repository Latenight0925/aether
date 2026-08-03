package dev.aether.modules.pest;

import dev.aether.config.AetherConfig;

import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.pest.helpers.PestAotvManager;
import dev.aether.modules.pest.helpers.AutoPestExchangeManager;
import dev.aether.modules.pest.helpers.PestBonusManager;
import dev.aether.modules.pest.helpers.PestPlotId;
import dev.aether.modules.pest.helpers.PestLifecycleManager;
import dev.aether.modules.pest.helpers.PestOnTheTrackManager;
import dev.aether.modules.pest.helpers.PestDestroyer;
import dev.aether.modules.pest.helpers.PestReturnManager;
import dev.aether.modules.GreenhouseManager;
import dev.aether.modules.CropFeverManager;
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.util.ClientUtils;

import net.minecraft.client.Minecraft;

import java.util.LinkedHashSet;
import java.util.Set;
import dev.aether.modules.pest.helpers.PestPrepSwapManager;
import dev.aether.modules.visitor.VisitorsMacro;

public class PestManager {
    // Shared state
    private static volatile boolean isCleaningInProgress = false;
    private static volatile String currentInfestedPlot = null;
    private static volatile Set<String> currentInfestedPlots = Set.of();
    private static volatile int currentPestSessionId = 0;
    private static final long PEST_REENTRY_COOLDOWN_MS = 30_000;
    private static long lastZeroPestTime = 0;
    private static volatile int predictedAliveCount = 0;
    private static volatile long lastChatSpawnUpdateMs = 0;
    private static volatile long lastLocalKillUpdateMs = 0;
    private static volatile boolean isCleaningTriggerPending = false;
    private static volatile long pestReentryCooldownUntilMs = 0;
    private static volatile int lastCleaningAliveCount = -1;
    private static volatile long lastCleaningProgressAtMs = 0L;
    private static volatile boolean rewarpTriggerAvailable = false;
    private static final long TAB_SYNC_GRACE_MS = 5000;
    private static final long CLEANING_STALL_TIMEOUT_MS = 30_000;
    private static int cachedTabTick = Integer.MIN_VALUE;
    private static int cachedTabConnectionId = 0;
    private static PestTabSnapshot cachedTabListData = null;

    public static boolean isCleaningInProgress() {
        return isCleaningInProgress;
    }

    public static void setCleaningInProgress(boolean cleaning) {
        isCleaningInProgress = cleaning;
    }

    public static String getCurrentInfestedPlot() {
        return currentInfestedPlot;
    }

    public static Set<String> getCurrentInfestedPlots() {
        return currentInfestedPlots;
    }

    public static void setCurrentInfestedPlots(Set<String> plots) {
        currentInfestedPlots = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(plots));
    }

    public static int getCurrentPestSessionId() {
        return currentPestSessionId;
    }
    private static boolean isThresholdMet(int aliveCount) {
        return aliveCount >= AetherConfig.PEST_THRESHOLD.get() || aliveCount >= 8;
    }

    public static boolean isPestDestroyerEnabled() {
        return AetherConfig.TRIGGER_PEST_ON_CHAT.get();
    }

    public static boolean arePestTrapsEnabled() {
        return AetherConfig.ENABLE_PEST_TRAPS.get();
    }

    private static boolean isPestReentryCooldownActive() {
        return getPestReentryCooldownRemainingMs() > 0;
    }

    public static long getPestReentryCooldownRemainingMs() {
        return Math.max(0L, pestReentryCooldownUntilMs - System.currentTimeMillis());
    }

    public static void startPestReentryCooldown() {
        pestReentryCooldownUntilMs = System.currentTimeMillis() + PEST_REENTRY_COOLDOWN_MS;
    }

    private static synchronized boolean claimCleaningTrigger() {
        if (isCleaningTriggerPending
                || isCleaningInProgress
                || isPestReentryCooldownActive()
                || PestDestroyer.isActive()
                || PestOnTheTrackManager.getInstance().isNotIdle()
                || ManualPestManager.isActive()
                || PestReturnManager.isFinishingInProgress()
                || PestReturnManager.isReturnToLocationActive()
                || LoadoutManager.isSwappingLoadout) {
            return false;
        }
        isCleaningTriggerPending = true;
        return true;
    }

    public static synchronized void clearCleaningTriggerPending() {
        isCleaningTriggerPending = false;
    }

    public static void markRewarpCompleted() {
        rewarpTriggerAvailable = true;
    }

    private static boolean canTriggerAfterRewarp() {
        return !AetherConfig.PEST_TRIGGER_ONLY_AFTER_REWARP.get() || rewarpTriggerAvailable;
    }

    private static void consumeRewarpTrigger() {
        if (AetherConfig.PEST_TRIGGER_ONLY_AFTER_REWARP.get()) {
            rewarpTriggerAvailable = false;
        }
    }

    /**
     * Parse infested plots directly from the current tab list.
     */
    public static Set<String> getInfestedPlotsFromTab(Minecraft client) {
        return new LinkedHashSet<>(parseTabList(client).infestedPlots());
    }

    /**
     * Raw pests-alive count from the current tab list. Returns -1 when the tab
     * has no pests line (i.e. no pests). Unlike {@link #getEffectiveAliveCountNow}
     * this is not blended with chat-predicted counts, so it drops back to 0/-1 as
     * soon as the tab clears - which is what manual pest handling relies on.
     */
    public static int getTabAliveCountNow(Minecraft client) {
        if (client == null || client.getConnection() == null || client.player == null) {
            return -1;
        }
        return parseTabList(client).aliveCount();
    }

    private static PestTabSnapshot parseTabList(Minecraft client) {
        if (client.getConnection() == null || client.player == null) {
            return PestTabSnapshot.empty();
        }

        int tick = client.player.tickCount;
        int connectionId = System.identityHashCode(client.getConnection());
        if (cachedTabListData != null && cachedTabTick == tick && cachedTabConnectionId == connectionId) {
            return cachedTabListData;
        }

        PestTabSnapshot data = PestTabSnapshot.read(client);

        cachedTabTick = tick;
        cachedTabConnectionId = connectionId;
        cachedTabListData = data;
        return data;
    }
    public static void reset() {
        isCleaningInProgress = false;
        currentInfestedPlot = null;
        currentInfestedPlots = Set.of();
        lastZeroPestTime = 0;
        predictedAliveCount = 0;
        lastChatSpawnUpdateMs = 0;
        lastLocalKillUpdateMs = 0;
        isCleaningTriggerPending = false;
        pestReentryCooldownUntilMs = 0;
        lastCleaningAliveCount = -1;
        lastCleaningProgressAtMs = 0L;
        rewarpTriggerAvailable = false;
        cachedTabTick = Integer.MIN_VALUE;
        cachedTabConnectionId = 0;
        cachedTabListData = null;
        currentPestSessionId++;

        PestPrepSwapManager.resetState();
        PestLifecycleManager.reset();
        PestReturnManager.resetState();
        PestAotvManager.resetState();
        PestBonusManager.resetState();
        AutoPestExchangeManager.reset();
        PestDestroyer.reset();
        ManualPestManager.reset();
        DynamicPestsManager.reset();
    }

    public static void checkTabListForPests(Minecraft client, MacroState.State currentState) {
        if (client.getConnection() == null || client.player == null || !MacroStateManager.isMacroRunning())
            return;
        if (!isPestDestroyerEnabled())
            return;

        if (isCleaningInProgress
                && currentState == MacroState.State.FARMING
                && !PestDestroyer.isActive()
                && !PestReturnManager.isFinishingInProgress()
                && !PestReturnManager.isReturnToLocationActive()) {
            isCleaningInProgress = false;
        }

        PestTabSnapshot data = parseTabList(client);
        syncPredictedAliveFromTab(data.aliveCount());
        int effectiveAlive = getEffectiveAliveCount(data.aliveCount());

        if (data.bonusInactive() != null) {
            PestBonusManager.setBonusInactive(data.bonusInactive());
        }

        // Handle prep swap flag updates based on cooldown
        if (data.cooldownSeconds() != -1) {
                PestPrepSwapManager.updatePrepSwapFlag(data.cooldownSeconds(), isCleaningInProgress);

            // Check if prep swap should be triggered
            boolean thresholdMet = isThresholdMet(effectiveAlive);
            if (!thresholdMet && PestPrepSwapManager.shouldTriggerPrepSwap(
                    currentState, data.cooldownSeconds(), isCleaningInProgress,
                    PestReturnManager.isReturnToLocationActive())) {
                PestPrepSwapManager.triggerPrepSwap();
            }
        }

        // Failsafe: if CLEANING and 0 pests for 10s, return to farming.
        // Do not apply this during SPRAYING because spray routes can legitimately
        // travel multiple plots with 0 alive pests between spray actions.
        if (currentState == MacroState.State.CLEANING
                && !GreenhouseManager.isRunning()
                && !ManualPestManager.isActive()) {
            updateCleaningProgressTracker(effectiveAlive);
            if (effectiveAlive <= 0) {
                if (lastZeroPestTime == 0) {
                    lastZeroPestTime = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - lastZeroPestTime > 10000) {
                    ClientUtils.sendMessage("\u00A7cFail-safe: No pests detected for 10s. Returning to farm.", true);
                    lastZeroPestTime = 0;
                    handlePestCleaningFinished(client);
                    return;
                }
            } else {
                lastZeroPestTime = 0;
            }

            if (shouldAbortCleaningForStall(effectiveAlive)) {
                ClientUtils.sendMessage("\u00A7cPest cleaner made no pest-count progress for 30s. Aborting cleaner.",
                        true);
                ClientUtils.sendDebugMessage("PestManager: aborting pest cleaner after 30s without alive-count progress. Last alive="
                                + lastCleaningAliveCount + ", current alive=" + effectiveAlive);
                resetCleaningProgressTracker();
                handlePestCleaningFinished(client);
                return;
            }
        } else {
            lastZeroPestTime = 0;
            resetCleaningProgressTracker();
        }

        if (isCleaningInProgress) {
            return;
        }

        // Do not trigger pest cleaning while the visitors macro is actively running.
        // Visitors takes priority; pest cleaning will be re-evaluated once we return
        // to the farm after visitors finishes.
        if (VisitorsMacro.isRunning) {
            return;
        }

        if (isThresholdMet(effectiveAlive)) {
            if (isPestReentryCooldownActive()) {
                return;
            }
            if (!canTriggerAfterRewarp()) {
                return;
            }
            // Priority: if exchange is enabled and the bonus is inactive, let exchange happen first.
            if (AetherConfig.AUTO_PEST_EXCHANGE.get() && PestBonusManager.isBonusInactive()) {
                return;
            }

            // Crop Fever delay: do not interrupt farming for pests if fever is active
            if (AetherConfig.DELAY_PEST_FOR_CROP_FEVER.get() && CropFeverManager.isCropFeverActive) {
                return;
            }

            if (effectiveAlive >= 8 && effectiveAlive < 99) {
                ClientUtils.sendMessage("\u00A7eMax Pests (8) reached. Starting cleaning...", true);
            }
            setCurrentInfestedPlots(data.infestedPlots());
            String targetPlot = data.infestedPlots().stream().findFirst().orElse(null);
            ClientUtils.sendDebugMessage("[PestManager] Tab threshold met. infestedPlots=" + data.infestedPlots()
                            + " targetPlot=" + targetPlot + " currentPlot=" + ClientUtils.getCurrentPlot());
            boolean started = startCleaningSequence(client, targetPlot, effectiveAlive);
            if (started) {
                consumeRewarpTrigger();
            }
        }
    }

    public static void handlePestCleaningFinished(Minecraft client) {
        clearCleaningTriggerPending();
        if (!PestReturnManager.isFinishingInProgress()) {
            startPestReentryCooldown();
        }
        if (PestDestroyer.isActive()) {
            PestDestroyer.stop(client);
        }
        PestLifecycleManager.startPostStage(client);
    }

    public static void update() {
        Minecraft client = Minecraft.getInstance();
        checkTabListForPests(client, MacroStateManager.getCurrentState());
    }

    /**
     * Returns effective pests alive count from tab/chat sync.
     * -1 means unavailable/unknown right now.
     */
    public static int getEffectiveAliveCountNow(Minecraft client) {
        if (client == null || client.getConnection() == null || client.player == null) {
            return -1;
        }

        PestTabSnapshot data = parseTabList(client);
        if (data.aliveCount() < 0 && predictedAliveCount <= 0) {
            return -1;
        }

        syncPredictedAliveFromTab(data.aliveCount());
        return getEffectiveAliveCount(data.aliveCount());
    }

    public static boolean startCleaningSequence(Minecraft client, String plot) {
        return startCleaningSequence(client, plot, Math.max(1, getEffectiveAliveCountNow(client)));
    }

    private static boolean startCleaningSequence(Minecraft client, String plot, int pestCount) {
        if (!claimCleaningTrigger()) {
            return false;
        }
        currentInfestedPlot = plot;
        currentPestSessionId++;
        resetCleaningProgressTracker();
        if (PestLifecycleManager.start(client, plot, pestCount, currentPestSessionId)) {
            return true;
        }
        clearCleaningTriggerPending();
        return false;
    }

    public static void handlePhillipMessage(Minecraft client, String text) {
        PestBonusManager.handlePhillipMessage(client, text, currentInfestedPlot);
    }

    public static boolean tryStartCleaningSequenceFromChat(Minecraft client, String requestedPlot, int spawnedCount) {
        if (!isPestDestroyerEnabled()
                || client == null
                || client.getConnection() == null
                || client.player == null
                || isCleaningInProgress) {
            return false;
        }

        if (spawnedCount > 0) {
            predictedAliveCount = Math.min(99, predictedAliveCount + spawnedCount);
            lastChatSpawnUpdateMs = System.currentTimeMillis();
        }

        PestTabSnapshot data = parseTabList(client);
        syncPredictedAliveFromTab(data.aliveCount());
        int effectiveAlive = getEffectiveAliveCount(data.aliveCount());

        if (!isThresholdMet(effectiveAlive)) {
            ClientUtils.sendDebugMessage("Chat pest trigger ignored: effective=" + effectiveAlive
                            + " (chat=" + predictedAliveCount + ", tab=" + data.aliveCount()
                            + ") < threshold=" + AetherConfig.PEST_THRESHOLD.get());
            return false;
        }

        if (isPestReentryCooldownActive()) {
            ClientUtils.sendDebugMessage("Chat pest trigger ignored: pest re-entry cooldown active for "
                    + getPestReentryCooldownRemainingMs() + "ms.");
            return false;
        }

        if (!canTriggerAfterRewarp()) {
            ClientUtils.sendDebugMessage("Chat pest trigger ignored: waiting for rewarp before triggering cleaner.");
            return false;
        }

        if (AetherConfig.DELAY_PEST_FOR_CROP_FEVER.get() && CropFeverManager.isCropFeverActive) {
            ClientUtils.sendDebugMessage("Chat pest trigger ignored: Crop Fever is currently active.");
            return false;
        }

        Set<String> candidatePlots = new LinkedHashSet<>(data.infestedPlots());
        String normalizedRequestedPlot = PestPlotId.normalize(requestedPlot);
        if (PestPlotId.isUsable(normalizedRequestedPlot)) {
            candidatePlots.add(normalizedRequestedPlot);
        }

        String targetPlot = candidatePlots.stream()
                .findFirst()
                .orElse(PestPlotId.isUsable(requestedPlot)
                        ? PestPlotId.normalize(requestedPlot)
                        : null);

        setCurrentInfestedPlots(candidatePlots);
        ClientUtils.sendDebugMessage("Chat pest trigger selecting plot " + targetPlot
                        + " from tab=" + data.infestedPlots()
                        + ", chat=" + normalizedRequestedPlot
                        + ", ordered=" + currentInfestedPlots);
        boolean started = startCleaningSequence(client, targetPlot, effectiveAlive);
        if (started) {
            consumeRewarpTrigger();
        }
        return started;
    }

    public static void decrementPredictedAliveCount(Minecraft client) {
        if (predictedAliveCount > 0) {
            predictedAliveCount--;
            lastLocalKillUpdateMs = System.currentTimeMillis();
            ClientUtils.sendDebugMessage("Pest kill detected! Predicted alive: " + predictedAliveCount
                            + ", currentPlot=" + ClientUtils.getCurrentPlot()
                            + ", whitelistedPlots=" + AetherConfig.LEAVE_ONE_PEST_PLOTS.get());

            if (PestDestroyer.isActive() && PestDestroyer.shouldFinishForAliveCount(client, predictedAliveCount)) {
                String reason = predictedAliveCount == 0
                        ? "0 pests predicted"
                        : "only whitelisted leave-one plot(s) predicted remaining";
                ClientUtils.sendDebugMessage("PestManager: threshold reached: " + reason + ". Finishing immediately.");
                PestDestroyer.finish(client);
            }
        }
    }

    private static void syncPredictedAliveFromTab(int tabAliveCount) {
        if (tabAliveCount < 0) {
            return;
        }

        long now = System.currentTimeMillis();
        if (tabAliveCount > predictedAliveCount && hasRecentLocalKill(now)) {
            return;
        }

        if (tabAliveCount >= predictedAliveCount) {
            predictedAliveCount = tabAliveCount;
            return;
        }

        if (now - lastChatSpawnUpdateMs > TAB_SYNC_GRACE_MS) {
            predictedAliveCount = tabAliveCount;
        }
    }

    private static int getEffectiveAliveCount(int tabAliveCount) {
        if (tabAliveCount < 0) {
            return predictedAliveCount;
        }
        if (tabAliveCount > predictedAliveCount && hasRecentLocalKill(System.currentTimeMillis())) {
            return predictedAliveCount;
        }
        return Math.max(tabAliveCount, predictedAliveCount);
    }

    private static boolean hasRecentLocalKill(long now) {
        return lastLocalKillUpdateMs > 0 && now - lastLocalKillUpdateMs <= TAB_SYNC_GRACE_MS;
    }

    private static void updateCleaningProgressTracker(int effectiveAlive) {
        if (effectiveAlive < 0) {
            return;
        }

        long now = System.currentTimeMillis();
        if (lastCleaningAliveCount < 0 || effectiveAlive < lastCleaningAliveCount) {
            lastCleaningAliveCount = effectiveAlive;
            lastCleaningProgressAtMs = now;
            return;
        }

        if (lastCleaningProgressAtMs == 0L) {
            lastCleaningAliveCount = effectiveAlive;
            lastCleaningProgressAtMs = now;
        }
    }

    private static boolean shouldAbortCleaningForStall(int effectiveAlive) {
        return effectiveAlive > 0
                && lastCleaningAliveCount >= 0
                && lastCleaningProgressAtMs > 0L
                && System.currentTimeMillis() - lastCleaningProgressAtMs >= CLEANING_STALL_TIMEOUT_MS;
    }

    private static void resetCleaningProgressTracker() {
        lastCleaningAliveCount = -1;
        lastCleaningProgressAtMs = 0L;
    }
}

