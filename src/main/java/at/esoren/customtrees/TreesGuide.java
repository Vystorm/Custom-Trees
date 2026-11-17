package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeSpecies;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Locale;

/**
 * Data of the web tree guide ({@code /data/trees/guide.json}): the species Core's tree builder knows and what the loaded
 * configuration enables (formations, triggers, catalyst costs, sizes). Only ids and numbers; the page translates them.
 * Contains nothing secret, so the module publishes it without a permission.
 */
final class TreesGuide {
    static final int FORMAT = 1;
    /** Architecture variants every species is built with (Core {@code TreeBuilder.TreeForm}). */
    static final String[] VARIANTS = {"open-grown", "forest", "windswept", "ancient"};

    private TreesGuide() {
    }

    static JsonObject build(TreeSettings settings) {
        JsonObject json = new JsonObject();
        json.addProperty("format", FORMAT);
        json.addProperty("growPermission", "customtrees.grow");
        JsonArray worlds = new JsonArray();
        settings.enabledWorlds().stream().sorted().forEach(worlds::add);
        json.add("worlds", worlds);

        boolean custom = settings.customPlayerGrownTrees();
        JsonObject single = new JsonObject();
        single.addProperty("enabled", custom);
        json.add("single", single);
        JsonObject square = new JsonObject();
        square.addProperty("enabled", custom);
        json.add("square", square);

        JsonObject shrubs = new JsonObject();
        shrubs.addProperty("enabled", custom && settings.connectedShrubsAndHedges());
        shrubs.addProperty("shrubMin", 2);
        shrubs.addProperty("shrubMax", GiantSaplingListener.HEDGE_MIN_LENGTH - 1);
        shrubs.addProperty("hedgeMin", GiantSaplingListener.HEDGE_MIN_LENGTH);
        shrubs.addProperty("max", GiantSaplingListener.MAX_CONNECTED_SAPLINGS);
        json.add("shrubs", shrubs);

        json.add("giant", size(settings.giantSaplings(), settings.naturalGiantGrowth(), settings.bonemealGiantGrowth(),
                settings.giantMinHeight(), settings.giantMaxHeight(), settings.giantCanopyRadius()));
        json.add("colossal", size(settings.colossalSaplings(), settings.naturalColossalGrowth(),
                settings.bonemealColossalGrowth(), settings.colossalMinHeight(), settings.colossalMaxHeight(),
                settings.colossalCanopyRadius()));

        JsonObject glowstone = new JsonObject();
        glowstone.addProperty("enabled", settings.glowstoneGrowth());
        glowstone.addProperty("xpCost", settings.glowstoneXpCost());
        glowstone.addProperty("leavesPerBlock", settings.glowstoneLeavesPerBlock());
        glowstone.addProperty("maxBlocks", settings.glowstoneMaxBlocks());
        glowstone.addProperty("darkLightLevel", settings.glowstoneDarkLightLevel());
        json.add("glowstone", glowstone);
        JsonObject coal = new JsonObject();
        coal.addProperty("enabled", settings.coalGrowth());
        json.add("coal", coal);

        JsonObject generation = new JsonObject();
        generation.addProperty("enabled", settings.replaceGeneratedTrees() && settings.postProcessNewChunks());
        generation.addProperty("chance", settings.replacementChance());
        json.add("generation", generation);

        JsonArray variants = new JsonArray();
        for (String variant : VARIANTS) variants.add(variant);
        json.add("variants", variants);
        JsonArray species = new JsonArray();
        for (TreeSpecies value : TreeSpecies.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", value.name().toLowerCase(Locale.ROOT));
            entry.addProperty("sapling", key(value.sapling.name()));
            entry.addProperty("log", key(value.log.name()));
            entry.addProperty("leaves", key(value.leaves.name()));
            entry.addProperty("shape", value.shape.name().toLowerCase(Locale.ROOT));
            entry.addProperty("minHeight", value.minHeight);
            entry.addProperty("maxHeight", value.maxHeight);
            species.add(entry);
        }
        json.add("species", species);
        return json;
    }

    private static JsonObject size(boolean enabled, boolean natural, boolean bonemeal, int minHeight, int maxHeight,
            int radius) {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("natural", natural);
        json.addProperty("bonemeal", bonemeal);
        json.addProperty("minHeight", minHeight);
        json.addProperty("maxHeight", maxHeight);
        json.addProperty("radius", radius);
        return json;
    }

    private static String key(String material) {
        return material.toLowerCase(Locale.ROOT);
    }
}
