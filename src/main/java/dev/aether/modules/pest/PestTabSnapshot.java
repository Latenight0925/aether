package dev.aether.modules.pest;

import dev.aether.modules.pest.helpers.PestBonusManager;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record PestTabSnapshot(
        int aliveCount,
        int cooldownSeconds,
        Boolean bonusInactive,
        Set<String> infestedPlots
) {
    private static final Pattern PESTS_ALIVE_PATTERN =
            Pattern.compile("(?i)(?:Pests|Alive):?\\s*\\(?(\\d+)\\)?");
    private static final Pattern COOLDOWN_PATTERN =
            Pattern.compile("(?i)Cooldown:\\s*\\(?(READY|MAX\\s*PESTS?|(?:(\\d+)m)?\\s*(?:(\\d+)s)?)\\)?");
    private static final Pattern INFESTED_PLOTS_PATTERN =
            Pattern.compile("(?i)Plots?:\\s*(.+)");

    PestTabSnapshot {
        infestedPlots = Collections.unmodifiableSet(new LinkedHashSet<>(infestedPlots));
    }

    static PestTabSnapshot empty() {
        return new PestTabSnapshot(-1, -1, null, Set.of());
    }

    static PestTabSnapshot read(Minecraft client) {
        if (client == null || client.getConnection() == null || client.player == null) {
            return empty();
        }
        return parse(TablistUtils.getRawTabLines(client));
    }

    static PestTabSnapshot parse(List<String> lines) {
        int aliveCount = -1;
        int cooldownSeconds = -1;
        Boolean bonusInactive = null;
        Set<String> infestedPlots = new LinkedHashSet<>();

        for (String normalized : lines) {
            Matcher aliveMatcher = PESTS_ALIVE_PATTERN.matcher(normalized);
            if (aliveMatcher.find()) {
                aliveCount = Math.max(aliveCount, Integer.parseInt(aliveMatcher.group(1)));
            }

            Matcher cooldownMatcher = COOLDOWN_PATTERN.matcher(normalized);
            if (cooldownMatcher.find()) {
                String value = cooldownMatcher.group(1).toUpperCase();
                if (value.contains("MAX PEST")) {
                    cooldownSeconds = 999;
                } else if (value.equals("READY")) {
                    cooldownSeconds = 0;
                } else {
                    int minutes = parseInt(cooldownMatcher.group(2));
                    int seconds = parseInt(cooldownMatcher.group(3));
                    if (minutes > 0 || seconds > 0) {
                        cooldownSeconds = minutes * 60 + seconds;
                    }
                }
            }

            Matcher plotsMatcher = INFESTED_PLOTS_PATTERN.matcher(normalized);
            if (plotsMatcher.find()) {
                for (String part : plotsMatcher.group(1).split(",")) {
                    String plot = part.trim().replaceAll("\\D", "");
                    if (!plot.isEmpty()) {
                        infestedPlots.add(plot);
                    }
                }
            }

            if (bonusInactive == null) {
                Boolean parsedBonus = PestBonusManager.parseBonusState(normalized);
                if (parsedBonus != null) {
                    bonusInactive = parsedBonus;
                }
            }
        }

        return new PestTabSnapshot(aliveCount, cooldownSeconds, bonusInactive, infestedPlots);
    }

    private static int parseInt(String value) {
        return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
    }
}
