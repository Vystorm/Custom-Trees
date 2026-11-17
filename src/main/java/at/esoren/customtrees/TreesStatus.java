package at.esoren.customtrees;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What {@code /customtrees status} reports: active switches and counters. One snapshot feeds both the chat line and the
 * admin panel of the web page, so both always show the same values. Build it on the server thread (the generated-tree
 * queue is not thread-safe).
 */
record TreesStatus(
        String version,
        TreeSettings settings,
        long grown,
        long giantGrown,
        long colossalGrown,
        long shrubs,
        long hedges,
        long glowstoneGrown,
        long coalGrown,
        long glowstoneBlocks,
        long coreProtectLogs,
        long inspectedChunks,
        long replaced,
        int queued,
        List<String> integrations,
        Map<String, Long> speciesGrown
) {
    TreesStatus(String version, TreeSettings settings, long grown, long giantGrown, long colossalGrown,
            long shrubs, long hedges, long glowstoneGrown, long coalGrown, long glowstoneBlocks,
            long coreProtectLogs, long inspectedChunks, long replaced, int queued, List<String> integrations) {
        this(version, settings, grown, giantGrown, colossalGrown, shrubs, hedges, glowstoneGrown, coalGrown,
                glowstoneBlocks, coreProtectLogs, inspectedChunks, replaced, queued, integrations, Map.of());
    }

    TreesStatus {
        integrations = List.copyOf(integrations);
        speciesGrown = Collections.unmodifiableMap(new LinkedHashMap<>(speciesGrown));
    }

    /** The chat line of {@code /customtrees status}; {@code worlds} is the localized world list. */
    String commandLine(String worlds) {
        String base = String.format(Locale.ROOT,
                "CustomTrees: player-grown=%s, grown=%d, giant-grown=%d, colossal-grown=%d, shrubs=%d, hedges=%d, glowstone-grown=%d, coal-grown=%d, glowstone-blocks=%d, coreprotect-logs=%d, replace-generated=%s (%.0f%%), new-chunk-pass=%s, inspected-chunks=%d, replaced=%d, queued=%d, giant-3x3=%s, colossal-5x5=%s, worlds=%s",
                settings.customPlayerGrownTrees(), grown, giantGrown, colossalGrown, shrubs, hedges,
                glowstoneGrown, coalGrown, glowstoneBlocks, coreProtectLogs, settings.replaceGeneratedTrees(),
                settings.replacementChance() * 100.0, settings.postProcessNewChunks(), inspectedChunks, replaced,
                queued, settings.giantSaplings(), settings.colossalSaplings(), worlds);
        return speciesGrown.isEmpty() ? base : base + ", species-grown=" + speciesSummary();
    }

    /** The admin panel's view: {@code counters} and {@code switches} in the order of the chat line. */
    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("version", version);
        JsonObject counters = new JsonObject();
        counters.addProperty("grown", grown);
        counters.addProperty("giant-grown", giantGrown);
        counters.addProperty("colossal-grown", colossalGrown);
        counters.addProperty("shrubs", shrubs);
        counters.addProperty("hedges", hedges);
        counters.addProperty("glowstone-grown", glowstoneGrown);
        counters.addProperty("coal-grown", coalGrown);
        counters.addProperty("glowstone-blocks", glowstoneBlocks);
        counters.addProperty("coreprotect-logs", coreProtectLogs);
        counters.addProperty("inspected-chunks", inspectedChunks);
        counters.addProperty("replaced", replaced);
        counters.addProperty("queued", queued);
        if (!speciesGrown.isEmpty()) counters.addProperty("species-grown", speciesSummary());
        json.add("counters", counters);
        JsonObject species = new JsonObject();
        speciesGrown.forEach(species::addProperty);
        json.add("speciesGrown", species);
        JsonObject switches = new JsonObject();
        switches.addProperty("player-grown", settings.customPlayerGrownTrees());
        switches.addProperty("shrubs-hedges", settings.connectedShrubsAndHedges());
        switches.addProperty("replace-generated", settings.replaceGeneratedTrees());
        switches.addProperty("new-chunk-pass", settings.postProcessNewChunks());
        switches.addProperty("giant-3x3", settings.giantSaplings());
        switches.addProperty("colossal-5x5", settings.colossalSaplings());
        switches.addProperty("glowstone", settings.glowstoneGrowth());
        switches.addProperty("coal", settings.coalGrowth());
        json.add("switches", switches);
        json.addProperty("replacementChance", settings.replacementChance());
        json.add("worlds", strings(settings.enabledWorlds().stream().sorted().toList()));
        json.add("integrations", strings(integrations));
        return json;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private String speciesSummary() {
        return speciesGrown.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
    }
}
