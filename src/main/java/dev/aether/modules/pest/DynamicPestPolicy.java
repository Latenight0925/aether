package dev.aether.modules.pest;

import java.util.ArrayList;
import java.util.List;

final class DynamicPestPolicy {
    private DynamicPestPolicy() {
    }

    static String resolve(
            int mode,
            boolean feastActive,
            List<String> feastCrops,
            List<String> feastPriority,
            List<String> contestCrops,
            List<String> contestPriority) {
        boolean contestActive = contestCrops != null && !contestCrops.isEmpty();
        return switch (mode) {
            case 0 -> feastActive
                    ? highestPriority(feastCrops, feastPriority)
                    : null;
            case 1 -> contestActive
                    ? highestPriority(contestCrops, contestPriority)
                    : null;
            case 2 -> {
                String contestCrop = contestActive
                        ? highestPriority(contestCrops, contestPriority)
                        : null;
                yield contestCrop != null
                        ? contestCrop
                        : feastActive
                                ? highestPriority(feastCrops, feastPriority)
                                : null;
            }
            default -> null;
        };
    }

    static List<String> parseContestCrops(List<String> tabLines) {
        List<String> crops = new ArrayList<>();
        for (String raw : tabLines) {
            String line = raw.trim();
            if (!line.startsWith("\u25CB ") && !line.startsWith("\u2618 ")) {
                continue;
            }
            String crop = normalizeCrop(line.substring(2).trim());
            if (PestCropData.BY_CROP.containsKey(crop)) {
                crops.add(crop);
            }
        }
        return List.copyOf(crops);
    }

    static String normalizeCrop(String crop) {
        return switch (crop) {
            case "Melon" -> "Melon Slice";
            case "Mushroom" -> "Mushrooms";
            default -> crop;
        };
    }

    private static String highestPriority(
            List<String> activeCrops,
            List<String> priority) {
        if (activeCrops == null || activeCrops.isEmpty()) {
            return null;
        }
        if (priority != null) {
            for (String crop : priority) {
                if (activeCrops.contains(crop)) {
                    return crop;
                }
            }
        }
        return activeCrops.getFirst();
    }
}
