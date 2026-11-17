package at.esoren.customtrees;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Settings of the built-in timber ({@code timber.*} in config.yml). Every value is clamped or falls back to its
 * default, so a typo never stops the plugin; the choice values avoid YAML's on/off/yes/no booleans.
 */
record TimberOptions(boolean enabled, Mode mode, Trigger trigger, int trunkPercent, Sneaking sneaking,
        boolean requireAxe, List<String> worlds, boolean playerChoice, Map<SizeClass, TypeMode> treeTypes,
        double durabilityPerLog, boolean spareTool, boolean leafDrops, boolean intoInventory,
        int maxBlocks, int maxRadius, int maxHeight, int minLeaves, boolean naturalGround,
        boolean coreProtectCheck, boolean onlyGeneratedTrees, int maxDisplays, int fallTicks,
        int blocksPerTick, int checksPerTick, int fallsPerWorld, int fallsPerPlayer, boolean pauseForOtherTimber,
        int percentMemorySeconds, List<String> protectedRegionKinds, boolean replant) {

    /** How a felled tree comes down. */
    enum Mode {
        /** Straight down: the tree breaks layer by layer from the cut upwards, items land under each block. */
        DROP,
        /** A real tree fall: the tree topples away from the player around its stump and turns into items. */
        FALL;

        String id() { return name().toLowerCase(Locale.ROOT); }

        static Mode parse(String value, Mode fallback) {
            if (value == null) return fallback;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "drop" -> DROP;
                case "fall" -> FALL;
                default -> fallback;
            };
        }
    }

    /** When a tree comes down. */
    enum Trigger {
        /** Breaking any log of the tree fells the whole tree. */
        FIRST_LOG,
        /** The tree falls once {@code trunk-percent} of the trunk cross-section at the chopped height is cut. */
        PERCENT;

        String id() { return this == FIRST_LOG ? "first-log" : "percent"; }

        static Trigger parse(String value, Trigger fallback) {
            if (value == null) return fallback;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "first-log", "first_log" -> FIRST_LOG;
                case "percent" -> PERCENT;
                default -> fallback;
            };
        }
    }

    /** Whether the player has to sneak (or must not sneak) for the timber to work. */
    enum Sneaking {
        STANDING, SNEAKING, ANY;

        String id() { return name().toLowerCase(Locale.ROOT); }

        boolean allows(boolean sneaking) {
            return this == ANY || (this == SNEAKING) == sneaking;
        }

        static Sneaking parse(String value, Sneaking fallback) {
            if (value == null) return fallback;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "standing" -> STANDING;
                case "sneaking" -> SNEAKING;
                case "any" -> ANY;
                default -> fallback;
            };
        }
    }

    /** Size class of a detected tree, from the trunk cross-section and the block count. */
    enum SizeClass {
        /** Single trees, 2x2 trees, shrubs and hedges. */
        SMALL,
        /** 3x3 giants. */
        GIANT,
        /** 5x5 colossi. */
        COLOSSAL,
        /** 10x10 world trees and everything bigger. */
        WORLD;

        String id() { return name().toLowerCase(Locale.ROOT); }

        /** @param trunk logs in the cut cross-section; @param blocks all blocks of the tree */
        static SizeClass of(int trunk, int blocks) {
            if (trunk > 48 || blocks > 20_000) return WORLD;
            if (trunk > 12) return COLOSSAL;
            if (trunk > 4) return GIANT;
            return SMALL;
        }
    }

    /** Per tree type: follow the server/player mode, force one mode, or keep the timber away from this type. */
    enum TypeMode {
        SERVER_MODE, DROP, FALL, DISABLED;

        String id() { return this == SERVER_MODE ? "server-mode" : name().toLowerCase(Locale.ROOT); }

        static TypeMode parse(String value, TypeMode fallback) {
            if (value == null) return fallback;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "server-mode", "server_mode", "server" -> SERVER_MODE;
                case "drop" -> DROP;
                case "fall" -> FALL;
                case "disabled" -> DISABLED;
                default -> fallback;
            };
        }
    }

    /** What a player picked in /vsettings (or {@link #SERVER} = no own choice). */
    enum PlayerMode {
        SERVER, DROP, FALL;

        String id() { return name().toLowerCase(Locale.ROOT); }

        static PlayerMode parse(String value) {
            if (value == null) return SERVER;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "drop" -> DROP;
                case "fall" -> FALL;
                default -> SERVER;
            };
        }
    }

    static final List<String> DEFAULT_REGION_KINDS = List.of("spawn", "safe", "arena", "arena-zone", "market", "dungeon", "structure");

    static final TimberOptions DEFAULTS = new TimberOptions(true, Mode.FALL, Trigger.FIRST_LOG, 80, Sneaking.STANDING,
            true, List.of(), true, defaultTypes(), 1.0, true, true, false,
            150_000, 64, 200, 4, true, true, false, 400, 40, 1500, 1000, 4, 1, true, 600, DEFAULT_REGION_KINDS, true);

    TimberOptions {
        worlds = List.copyOf(worlds);
        treeTypes = Map.copyOf(treeTypes);
        protectedRegionKinds = List.copyOf(protectedRegionKinds);
    }

    private static Map<SizeClass, TypeMode> defaultTypes() {
        Map<SizeClass, TypeMode> types = new EnumMap<>(SizeClass.class);
        for (SizeClass size : SizeClass.values()) types.put(size, TypeMode.SERVER_MODE);
        return types;
    }

    /** Reads {@code timber.*}; missing or broken values use the defaults, numbers are clamped to their range. */
    static TimberOptions read(ConfigurationSection root) {
        TimberOptions d = DEFAULTS;
        ConfigurationSection c = root == null ? null : root.getConfigurationSection("timber");
        if (c == null) return d;
        Map<SizeClass, TypeMode> types = new EnumMap<>(SizeClass.class);
        for (SizeClass size : SizeClass.values()) {
            types.put(size, TypeMode.parse(c.getString("tree-types." + size.id()), TypeMode.SERVER_MODE));
        }
        List<String> worlds = new ArrayList<>();
        for (String world : c.getStringList("worlds")) if (world != null && !world.isBlank()) worlds.add(world.trim());
        List<String> kinds = new ArrayList<>();
        if (c.isList("protected-region-kinds")) {
            for (String kind : c.getStringList("protected-region-kinds"))
                if (kind != null && !kind.isBlank()) kinds.add(kind.trim().toLowerCase(Locale.ROOT));
        } else kinds.addAll(DEFAULT_REGION_KINDS);
        double durability = c.getDouble("tool.durability-per-log", d.durabilityPerLog());
        if (!Double.isFinite(durability)) durability = d.durabilityPerLog();
        String leaves = c.getString("drops.leaves", "vanilla");
        return new TimberOptions(
                c.getBoolean("enabled", d.enabled()),
                Mode.parse(c.getString("mode"), d.mode()),
                Trigger.parse(c.getString("trigger"), d.trigger()),
                Math.clamp(c.getInt("trunk-percent", d.trunkPercent()), 1, 100),
                Sneaking.parse(c.getString("sneaking"), d.sneaking()),
                c.getBoolean("require-axe", d.requireAxe()),
                worlds,
                c.getBoolean("player-choice", d.playerChoice()),
                types,
                Math.clamp(durability, 0.0, 10.0),
                c.getBoolean("tool.spare-tool", d.spareTool()),
                !"none".equalsIgnoreCase(leaves == null ? "" : leaves.trim()),
                c.getBoolean("drops.into-inventory", d.intoInventory()),
                Math.clamp(c.getInt("detection.max-blocks", d.maxBlocks()), 16, 250_000),
                Math.clamp(c.getInt("detection.max-radius", d.maxRadius()), 4, 96),
                Math.clamp(c.getInt("detection.max-height", d.maxHeight()), 8, 384),
                Math.clamp(c.getInt("detection.min-leaves", d.minLeaves()), 0, 1000),
                c.getBoolean("detection.natural-ground", d.naturalGround()),
                c.getBoolean("detection.coreprotect-check", d.coreProtectCheck()),
                c.getBoolean("detection.only-generated-trees", d.onlyGeneratedTrees()),
                Math.clamp(c.getInt("fall.max-displays", d.maxDisplays()), 16, 2000),
                Math.clamp(c.getInt("fall.fall-ticks", d.fallTicks()), 10, 200),
                Math.clamp(c.getInt("performance.blocks-per-tick", d.blocksPerTick()), 64, 20_000),
                Math.clamp(c.getInt("performance.checks-per-tick", d.checksPerTick()), 64, 20_000),
                Math.clamp(c.getInt("performance.falls-per-world", d.fallsPerWorld()), 1, 64),
                Math.clamp(c.getInt("performance.falls-per-player", d.fallsPerPlayer()), 1, 16),
                !"run-both".equalsIgnoreCase(String.valueOf(c.getString("other-timber-plugins", "pause")).trim()),
                Math.clamp(c.getInt("percent-memory-seconds", d.percentMemorySeconds()), 10, 86_400),
                kinds,
                c.getBoolean("replant", d.replant()));
    }

    /** Whether the timber may run in this world ({@code worlds} empty = every world). */
    boolean worldAllowed(String world) {
        if (worlds.isEmpty()) return true;
        for (String name : worlds) if (name.equalsIgnoreCase(world)) return true;
        return false;
    }

    /**
     * The mode a tree comes down with, or null when the timber must not touch this tree type. A tree type set to
     * {@code disabled} always wins, then a forced type mode, then the player's own choice (when allowed), then the
     * server mode.
     */
    Mode effectiveMode(SizeClass size, PlayerMode playerMode) {
        TypeMode type = treeTypes.getOrDefault(size, TypeMode.SERVER_MODE);
        return switch (type) {
            case DISABLED -> null;
            case DROP -> Mode.DROP;
            case FALL -> Mode.FALL;
            case SERVER_MODE -> !playerChoice || playerMode == null || playerMode == PlayerMode.SERVER ? mode
                    : playerMode == PlayerMode.DROP ? Mode.DROP : Mode.FALL;
        };
    }

    /** Logs of the trunk cross-section that must be cut before the tree falls (at least one). */
    static int requiredCuts(int trunk, int percent) {
        if (trunk <= 0) return 1;
        return Math.max(1, Math.min(trunk, (int) Math.ceil(trunk * Math.clamp(percent, 1, 100) / 100.0 - 1e-9)));
    }
}
