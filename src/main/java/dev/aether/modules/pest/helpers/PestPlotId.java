package dev.aether.modules.pest.helpers;

public final class PestPlotId {
    private PestPlotId() {
    }

    public static String normalize(String plot) {
        if (plot == null) {
            return "";
        }
        String normalized = plot.trim().toLowerCase();
        String digits = normalized.replaceAll("\\D", "");
        return digits.isEmpty() ? normalized : digits;
    }

    public static boolean equals(String first, String second) {
        return normalize(first).equals(normalize(second));
    }

    public static boolean isUsable(String plot) {
        String normalized = normalize(plot);
        return !normalized.isEmpty()
                && !normalized.equals("0")
                && !normalized.equals("unknown");
    }
}
