package dev.aether.modules.pest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record HarvestFeastSnapshot(
        boolean active,
        List<String> activeCrops,
        Map<String, Long> nextCropTimestamps,
        long nextFetchMs
) {
    HarvestFeastSnapshot {
        activeCrops = List.copyOf(activeCrops);
        nextCropTimestamps = Collections.unmodifiableMap(new LinkedHashMap<>(nextCropTimestamps));
    }

    static HarvestFeastSnapshot parse(String responseBody, long now, long fallbackPollMs) {
        JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
        boolean complete = json.has("complete") && json.get("complete").getAsBoolean();
        JsonArray current = json.has("current") ? json.getAsJsonArray("current") : null;
        if (!complete || current == null || current.isEmpty()) {
            return new HarvestFeastSnapshot(false, List.of(), Map.of(), now + fallbackPollMs);
        }

        List<String> crops = new ArrayList<>(current.size());
        for (JsonElement element : current) {
            crops.add(DynamicPestPolicy.normalizeCrop(element.getAsString()));
        }

        long earliest = Long.MAX_VALUE;
        Map<String, Long> next = new LinkedHashMap<>();
        if (json.has("next")) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("next").entrySet()) {
                if (entry.getValue().isJsonNull()) {
                    continue;
                }
                long timestampMs = entry.getValue().getAsLong() * 1000L;
                next.put(DynamicPestPolicy.normalizeCrop(entry.getKey()), timestampMs);
                if (timestampMs > now && timestampMs < earliest) {
                    earliest = timestampMs;
                }
            }
        }

        long nextFetch = earliest == Long.MAX_VALUE ? now + fallbackPollMs : earliest;
        return new HarvestFeastSnapshot(true, crops, next, nextFetch);
    }
}
