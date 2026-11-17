package at.esoren.customtrees;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

record TreeSettings(
        Set<String> enabledWorlds,
        boolean customPlayerGrownTrees,
        boolean replaceGeneratedTrees,
        double replacementChance,
        int scanDepth,
        boolean postProcessNewChunks,
        int postProcessDelayTicks,
        boolean giantSaplings,
        int giantMinHeight,
        int giantMaxHeight,
        int giantCanopyRadius,
        boolean naturalGiantGrowth,
        boolean bonemealGiantGrowth,
        boolean colossalSaplings,
        int colossalMinHeight,
        int colossalMaxHeight,
        int colossalCanopyRadius,
        boolean naturalColossalGrowth,
        boolean bonemealColossalGrowth,
        boolean connectedShrubsAndHedges,
        boolean mixedHedges,
        boolean glowstoneGrowth,
        int glowstoneXpCost,
        int glowstoneLeavesPerBlock,
        int glowstoneMaxBlocks,
        int glowstoneDarkLightLevel,
        boolean coalGrowth,
        int glowstoneGrowthCooldownSeconds,
        int glowstoneGrowthDailyLimit,
        int coalGrowthCooldownSeconds,
        int coalGrowthDailyLimit,
        CoalGrowthCosts coalGrowthCosts,
        boolean fireProtectTrunks,
        boolean fireProtectCrowns,
        boolean spruceGround,
        boolean spruceSnow,
        boolean spruceSnowColdOnly
) {
    static final int MIN_SCAN_DEPTH = 24;
    static final int MAX_SCAN_DEPTH = 160;
    static final int MAX_GLOWSTONE_XP_COST = 10_000;
    static final int MAX_CATALYST_COOLDOWN_SECONDS = 604_800;
    static final int MAX_CATALYST_DAILY_LIMIT = 1_000;

    TreeSettings {
        enabledWorlds = Set.copyOf(enabledWorlds);
        Objects.requireNonNull(coalGrowthCosts, "coalGrowthCosts");
    }

    static TreeSettings load(FileConfiguration config) {
        Set<String> worlds = new HashSet<>();
        for (String world : config.getStringList("settings.enabled-worlds")) {
            worlds.add(world.toLowerCase(Locale.ROOT));
        }
        double chance = config.getDouble("settings.replacement-chance", 1.0);
        int minHeight = config.getInt("settings.giant-min-height", 36);
        int maxHeight = config.getInt("settings.giant-max-height", 48);
        int radius = config.getInt("settings.giant-canopy-radius", 11);
        int colossalMinHeight = config.getInt("settings.colossal-min-height", 52);
        int colossalMaxHeight = config.getInt("settings.colossal-max-height", 68);
        int colossalRadius = config.getInt("settings.colossal-canopy-radius", 17);
        if (!Double.isFinite(chance) || chance < 0.0 || chance > 1.0) {
            throw new TreeSettingsException("validation.replacement-chance", "min", 0, "max", 1);
        }
        if (minHeight < 16 || maxHeight < minHeight || maxHeight > 96) {
            throw new TreeSettingsException("validation.giant-heights", "min", 16, "max", 96);
        }
        if (radius < 5 || radius > 20) {
            throw new TreeSettingsException("validation.giant-canopy-radius", "min", 5, "max", 20);
        }
        if (colossalMinHeight < 32 || colossalMaxHeight < colossalMinHeight
                || colossalMaxHeight > 120) {
            throw new TreeSettingsException("validation.colossal-heights", "min", 32, "max", 120);
        }
        if (colossalRadius < 10 || colossalRadius > 28) {
            throw new TreeSettingsException("validation.colossal-canopy-radius", "min", 10, "max", 28);
        }
        int glowstoneXpCost = config.getInt("settings.glowstone-xp-cost", 100);
        int glowstoneLeavesPerBlock = config.getInt("settings.glowstone-leaves-per-block", 80);
        int glowstoneMaxBlocks = config.getInt("settings.glowstone-max-blocks", 32);
        int glowstoneDarkLightLevel = config.getInt("settings.glowstone-dark-light-level", 7);
        int glowstoneCooldown = config.getInt("settings.glowstone-growth-cooldown-seconds", 0);
        int glowstoneDailyLimit = config.getInt("settings.glowstone-growth-daily-limit", 0);
        int coalCooldown = config.getInt("settings.coal-growth-cooldown-seconds", 0);
        int coalDailyLimit = config.getInt("settings.coal-growth-daily-limit", 0);
        if (glowstoneXpCost < 0 || glowstoneXpCost > MAX_GLOWSTONE_XP_COST) {
            throw new TreeSettingsException("validation.glowstone-xp-cost", "min", 0,
                    "max", MAX_GLOWSTONE_XP_COST);
        }
        int scanDepth = config.getInt("settings.generated-tree-scan-depth", 80);
        if (scanDepth < MIN_SCAN_DEPTH || scanDepth > MAX_SCAN_DEPTH) {
            throw new TreeSettingsException("validation.generated-tree-scan-depth", "min", MIN_SCAN_DEPTH,
                    "max", MAX_SCAN_DEPTH);
        }
        if (glowstoneLeavesPerBlock < 16 || glowstoneLeavesPerBlock > 1024) {
            throw new TreeSettingsException("validation.glowstone-leaves-per-block", "min", 16, "max", 1024);
        }
        if (glowstoneMaxBlocks < 1 || glowstoneMaxBlocks > 128) {
            throw new TreeSettingsException("validation.glowstone-max-blocks", "min", 1, "max", 128);
        }
        if (glowstoneDarkLightLevel < 0 || glowstoneDarkLightLevel > 15) {
            throw new TreeSettingsException("validation.glowstone-dark-light-level", "min", 0, "max", 15);
        }
        int postProcessDelayTicks = config.getInt("settings.new-chunk-post-process-delay-ticks", 2);
        if (postProcessDelayTicks < 1 || postProcessDelayTicks > 40) {
            throw new TreeSettingsException("validation.new-chunk-post-process-delay-ticks", "min", 1, "max", 40);
        }
        validateCatalystLimit("glowstone", glowstoneCooldown, glowstoneDailyLimit);
        validateCatalystLimit("coal", coalCooldown, coalDailyLimit);
        boolean shrubsAndHedges = config.contains("settings.connected-shrubs-and-hedges")
                ? config.getBoolean("settings.connected-shrubs-and-hedges", true)
                : config.getBoolean("settings.linear-shrubs-and-hedges", true);
        return new TreeSettings(
                worlds,
                config.getBoolean("settings.custom-player-grown-trees", true),
                config.getBoolean("settings.replace-generated-trees", true),
                chance,
                scanDepth,
                config.getBoolean("settings.new-chunk-post-processing", true),
                postProcessDelayTicks,
                config.getBoolean("settings.giant-3x3-saplings", true),
                minHeight,
                maxHeight,
                radius,
                config.getBoolean("settings.natural-3x3-growth", true),
                config.getBoolean("settings.bonemeal-3x3-growth", true),
                config.getBoolean("settings.colossal-5x5-saplings", true),
                colossalMinHeight,
                colossalMaxHeight,
                colossalRadius,
                config.getBoolean("settings.natural-5x5-growth", true),
                config.getBoolean("settings.bonemeal-5x5-growth", true),
                shrubsAndHedges,
                config.getBoolean("settings.mixed-hedges", false),
                config.getBoolean("settings.glowstone-growth", true),
                glowstoneXpCost,
                glowstoneLeavesPerBlock,
                glowstoneMaxBlocks,
                glowstoneDarkLightLevel,
                config.getBoolean("settings.coal-growth", true),
                glowstoneCooldown,
                glowstoneDailyLimit,
                coalCooldown,
                coalDailyLimit,
                CoalGrowthCosts.load(config),
                config.getBoolean("settings.fire-protect-trunks", false),
                config.getBoolean("settings.fire-protect-crowns", false),
                config.getBoolean("settings.spruce-ground", true),
                config.getBoolean("settings.spruce-snow", true),
                config.getBoolean("settings.spruce-snow-cold-only", true));
    }

    private static void validateCatalystLimit(String catalyst, int cooldown, int dailyLimit) {
        if (cooldown < 0 || cooldown > MAX_CATALYST_COOLDOWN_SECONDS) {
            throw new TreeSettingsException("validation.catalyst-cooldown", "catalyst", catalyst,
                    "min", 0, "max", MAX_CATALYST_COOLDOWN_SECONDS);
        }
        if (dailyLimit < 0 || dailyLimit > MAX_CATALYST_DAILY_LIMIT) {
            throw new TreeSettingsException("validation.catalyst-daily-limit", "catalyst", catalyst,
                    "min", 0, "max", MAX_CATALYST_DAILY_LIMIT);
        }
    }

    boolean enables(String worldName, World.Environment environment) {
        return environment == World.Environment.NORMAL
                && (enabledWorlds.isEmpty() || enabledWorlds.contains(worldName.toLowerCase(Locale.ROOT)));
    }
}
