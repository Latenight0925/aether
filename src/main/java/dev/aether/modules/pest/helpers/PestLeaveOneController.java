package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

import java.util.LinkedHashSet;
import java.util.Set;

/** Encapsulates the optional "leave one pest alive" plot policy. */
final class PestLeaveOneController {
    interface Context {
        String getEffectivePlot(Minecraft client);

        int countAvailablePests(Minecraft client);

        int countVisiblePestSkulls(Minecraft client);

        void setState(PestDestroyer.State state);
    }

    private PestLeaveOneController() {
    }

    static boolean shouldFinishForAliveCount(
            Minecraft client,
            PestDestroyerRuntime runtime,
            int aliveCount) {
        if (aliveCount < 0) {
            return false;
        }
        if (aliveCount == 0) {
            return true;
        }
        if (!AetherConfig.LEAVE_ONE_PEST_ALIVE.get()) {
            return false;
        }

        Set<String> leaveOnePlots =
                new LinkedHashSet<>(runtime.navigation.leaveOneSkippedPlots);
        for (String plot : PestManager.getInfestedPlotsFromTab(client)) {
            String normalized = PestPlotId.normalize(plot);
            if (runtime.navigation.leaveOneSkippedPlots.contains(normalized)) {
                continue;
            }
            if (!isWhitelistedPlot(plot)) {
                return false;
            }
            leaveOnePlots.add(normalized);
        }
        return !leaveOnePlots.isEmpty() && aliveCount <= leaveOnePlots.size();
    }

    static boolean tryLeaveCurrentPlot(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Context context) {
        if (!AetherConfig.LEAVE_ONE_PEST_ALIVE.get()) {
            return false;
        }

        String currentPlot = context.getEffectivePlot(client);
        if (!isWhitelistedPlot(currentPlot)) {
            return false;
        }

        String normalized = PestPlotId.normalize(currentPlot);
        if (runtime.navigation.leaveOneSkippedPlots.contains(normalized)) {
            return false;
        }

        int localPests = Math.max(
                context.countAvailablePests(client),
                context.countVisiblePestSkulls(client));
        int aliveNow = PestManager.getEffectiveAliveCountNow(client);
        boolean hasLocalEvidence = localPests > 0 || !runtime.killedEntities.isEmpty();
        boolean shouldSkip = localPests == 1
                || (aliveNow == 1
                        && hasOnlyCurrentActivePlot(client, runtime, currentPlot)
                        && (hasLocalEvidence
                                || PestCompletionGuard.isStartupGraceElapsed(runtime.activatedAt)));
        if (!shouldSkip) {
            return false;
        }

        runtime.navigation.leaveOneSkippedPlots.add(normalized);
        ClientUtils.sendDebugMessage(
                "PestDestroyer: leaving one pest alive on whitelisted plot "
                        + currentPlot
                        + ".");
        moveToNextActivePlotOrFinish(client, runtime, context);
        return true;
    }

    static Set<String> filterSkippedPlots(
            PestDestroyerRuntime runtime,
            Set<String> infested) {
        Set<String> filtered = new LinkedHashSet<>();
        for (String plot : infested) {
            if (!runtime.navigation.leaveOneSkippedPlots.contains(PestPlotId.normalize(plot))) {
                filtered.add(plot);
            }
        }
        return filtered;
    }

    private static void moveToNextActivePlotOrFinish(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Context context) {
        ClientUtils.setKeyMappingState(client.options.keyUse, false);
        ClientUtils.setKeyMappingState(client.options.keyDown, false);
        ClientUtils.setKeyMappingState(client.options.keyAttack, false);
        ClientUtils.setKeyMappingState(client.options.keyUp, false);
        PathfindingManager.stop();
        runtime.currentTarget = null;
        runtime.pestTargetQueue.clear();
        runtime.zeroPestTabTicks = 0;
        runtime.navigation.plotTpSent = false;
        runtime.navigation.getLocationAttempts = 0;
        runtime.navigation.waypointCycleCount = 0;
        Set<String> activeInfested = filterSkippedPlots(
                runtime,
                PestManager.getInfestedPlotsFromTab(client));
        runtime.navigation.plotQueue.clear();
        runtime.navigation.plotQueue.addAll(activeInfested);
        runtime.navigation.currentPlotIdx = 0;

        if (runtime.navigation.plotQueue.isEmpty()) {
            context.setState(PestDestroyer.State.FINISH);
            return;
        }

        String nextPlot = runtime.navigation.plotQueue.getFirst();
        String currentPlot = context.getEffectivePlot(client);
        if (!PestPlotId.equals(nextPlot, currentPlot)) {
            ClientUtils.sendDebugMessage(
                    "PestDestroyer: moving from skipped plot "
                            + currentPlot
                            + " to plot "
                            + nextPlot
                            + ".");
            context.setState(PestDestroyer.State.TELEPORT_TO_PLOT);
            return;
        }
        context.setState(PestDestroyer.State.CHECK_NEXT);
    }

    private static boolean hasOnlyCurrentActivePlot(
            Minecraft client,
            PestDestroyerRuntime runtime,
            String currentPlot) {
        Set<String> active = filterSkippedPlots(
                runtime,
                PestManager.getInfestedPlotsFromTab(client));
        return active.size() == 1
                && PestPlotId.equals(active.iterator().next(), currentPlot);
    }

    private static boolean isWhitelistedPlot(String plot) {
        String normalized = PestPlotId.normalize(plot);
        if (normalized.isEmpty() || normalized.equals("unknown")) {
            return false;
        }
        return AetherConfig.LEAVE_ONE_PEST_PLOTS.get().stream()
                .map(PestPlotId::normalize)
                .anyMatch(normalized::equals);
    }
}
