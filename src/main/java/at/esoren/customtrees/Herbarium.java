package at.esoren.customtrees;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The herbarium: per player, every tree and fungus form they have grown themselves (9 tree species in 7 forms, 4 fungi
 * in 5 sizes), with forester ranks for the number of forms found. Pure data and YAML (de)serialization; the plugin glue
 * lives in {@link HerbariumFeature}. Server thread only.
 */
final class Herbarium {
    /** Forms of the nine tree species: formation sizes, hedge, luminous tree and the 10x10 world tree. */
    static final List<Stage> TREE_STAGES = List.of(Stage.SINGLE, Stage.MEDIUM, Stage.GIANT, Stage.COLOSSAL,
            Stage.HEDGE, Stage.LUMINOUS, Stage.WORLD);
    /** Sizes of the four mushrooms and Nether fungi. */
    static final List<Stage> FUNGUS_STAGES = List.of(Stage.FUNGUS_1, Stage.FUNGUS_2, Stage.FUNGUS_3, Stage.FUNGUS_5,
            Stage.FUNGUS_10);
    /** Upper bound of stored ids per player (known plus unknown ones kept from newer versions). */
    static final int MAX_IDS_PER_PLAYER = 512;
    static final int FORMAT = 1;

    enum Plant {
        OAK(Material.OAK_SAPLING), BIRCH(Material.BIRCH_SAPLING), SPRUCE(Material.SPRUCE_SAPLING),
        JUNGLE(Material.JUNGLE_SAPLING), ACACIA(Material.ACACIA_SAPLING), DARK_OAK(Material.DARK_OAK_SAPLING),
        MANGROVE(Material.MANGROVE_PROPAGULE), CHERRY(Material.CHERRY_SAPLING), PALE_OAK(PaperCompat.material("PALE_OAK_SAPLING")),
        RED_MUSHROOM(Material.RED_MUSHROOM), BROWN_MUSHROOM(Material.BROWN_MUSHROOM),
        CRIMSON_FUNGUS(Material.CRIMSON_FUNGUS), WARPED_FUNGUS(Material.WARPED_FUNGUS);

        final Material icon;
        Plant(Material icon) { this.icon = icon; }

        static Plant[] available() { return java.util.Arrays.stream(values()).filter(p -> p.icon != null).toArray(Plant[]::new); }
        String id() { return name().toLowerCase(Locale.ROOT); }
        boolean fungus() { return ordinal() >= RED_MUSHROOM.ordinal(); }
        List<Stage> stages() { return fungus() ? FUNGUS_STAGES : TREE_STAGES; }

        /** The plant whose sapling, propagule, mushroom or fungus item/block is {@code material}, else null. */
        static Plant of(Material material) {
            for (Plant plant : values()) if (plant.icon != null && plant.icon == material) return plant;
            return null;
        }
    }

    enum Stage {
        SINGLE("single"), MEDIUM("medium"), GIANT("giant"), COLOSSAL("colossal"), HEDGE("hedge"),
        LUMINOUS("luminous"), WORLD("world"),
        FUNGUS_1("1x1"), FUNGUS_2("2x2"), FUNGUS_3("3x3"), FUNGUS_5("5x5"), FUNGUS_10("10x10");

        final String id;
        Stage(String id) { this.id = id; }

        /** Form of a sapling growth by its formation class (single saplings and shrubs count as single). */
        static Stage of(CoalGrowthCosts.GrowthClass growthClass) {
            return switch (growthClass) {
                case NORMAL -> SINGLE;
                case MEDIUM -> MEDIUM;
                case GIANT -> GIANT;
                case COLOSSAL -> COLOSSAL;
                case HEDGE -> HEDGE;
            };
        }

        /** Fungus size by formation width, or null for an unknown width. */
        static Stage fungus(int width) {
            return switch (width) {
                case 1 -> FUNGUS_1;
                case 2 -> FUNGUS_2;
                case 3 -> FUNGUS_3;
                case 5 -> FUNGUS_5;
                case 10 -> FUNGUS_10;
                default -> null;
            };
        }
    }

    /** Every collectable form in menu order. */
    static final List<String> ENTRIES;
    static final int TOTAL;
    static {
        List<String> all = new ArrayList<>();
        for (Plant plant : Plant.available()) for (Stage stage : plant.stages()) all.add(entry(plant, stage));
        ENTRIES = List.copyOf(all);
        TOTAL = ENTRIES.size();
    }

    enum Rank {
        SPROUT(0), GARDENER(5), FORESTER(15), ARBORIST(30), WARDEN(50), ELDER(70), KEEPER(Integer.MAX_VALUE);

        private final int min;
        Rank(int min) { this.min = min; }

        /** Forms needed for this rank; the keeper needs every form. */
        int needed() { return this == KEEPER ? TOTAL : min; }
        String id() { return name().toLowerCase(Locale.ROOT); }
        Rank next() { return this == KEEPER ? null : values()[ordinal() + 1]; }

        static Rank of(int found) {
            Rank result = SPROUT;
            for (Rank rank : values()) if (found >= rank.needed()) result = rank;
            return result;
        }

        static Rank byId(String id) {
            for (Rank rank : values()) if (rank.id().equalsIgnoreCase(id)) return rank;
            return null;
        }
    }

    /**
     * {@code herbarium.*} in config.yml. {@code rankCommands} are console commands per rank id ({player} = name).
     */
    record Options(boolean enabled, boolean announceRanks, Map<Rank, List<String>> rankCommands) {
        Options {
            rankCommands = Collections.unmodifiableMap(new LinkedHashMap<>(rankCommands));
        }

        static Options read(FileConfiguration config) {
            Map<Rank, List<String>> commands = new LinkedHashMap<>();
            ConfigurationSection section = config.getConfigurationSection("herbarium.rank-commands");
            if (section != null) for (String key : section.getKeys(false)) {
                Rank rank = Rank.byId(key);
                if (rank == null) continue;
                List<String> lines = section.getStringList(key).stream()
                        .map(String::trim).map(line -> line.startsWith("/") ? line.substring(1) : line)
                        .filter(line -> !line.isEmpty()).limit(16).toList();
                if (!lines.isEmpty()) commands.put(rank, lines);
            }
            return new Options(config.getBoolean("herbarium.enabled", true),
                    config.getBoolean("herbarium.announce-ranks", true), commands);
        }
    }

    /** Result of {@link #record}: {@code added} for a new form, {@code rankUp} when it reached a new rank. */
    record Recorded(boolean added, Rank rankUp, int found) {
        static final Recorded NOTHING = new Recorded(false, null, -1);
    }

    /** A leaderboard line. */
    record Standing(UUID id, String name, int found) {}

    record Suggestion(Plant plant, Stage stage) {
        int width() {
            return switch (stage) {
                case MEDIUM, FUNGUS_2 -> 2;
                case GIANT, FUNGUS_3 -> 3;
                case COLOSSAL, FUNGUS_5 -> 5;
                case WORLD, FUNGUS_10 -> 10;
                default -> 1;
            };
        }

        int plants() { return stage == Stage.HEDGE ? 4 : width() * width(); }

        String recipe() {
            if (stage == Stage.HEDGE) return "hedge";
            if (stage == Stage.LUMINOUS) return "luminous";
            return plant.fungus() || stage == Stage.WORLD ? "explicit" : "sapling";
        }
    }

    /** Prefer the smallest missing planting; ties follow the stable species and stage order. Read-only. */
    Suggestion next(UUID player, Plant filter) {
        Suggestion best = null;
        for (Plant plant : Plant.available()) {
            if (filter != null && plant != filter) continue;
            for (Stage stage : plant.stages()) {
                if (has(player, plant, stage)) continue;
                Suggestion candidate = new Suggestion(plant, stage);
                if (best == null || candidate.plants() < best.plants()) best = candidate;
            }
        }
        return best;
    }

    private static final class Collector {
        String name;
        final Set<String> ids = new LinkedHashSet<>();
        Collector(String name) { this.name = name; }
    }

    private final Map<UUID, Collector> collectors = new HashMap<>();
    private boolean dirty;

    static String entry(Plant plant, Stage stage) { return plant.id() + ":" + stage.id; }

    /** Adds a form for a player; a form already found changes nothing (except a new player name). */
    Recorded record(UUID player, String name, Plant plant, Stage stage) {
        if (player == null || plant == null || plant.icon == null || stage == null || !plant.stages().contains(stage)) return Recorded.NOTHING;
        Collector collector = collectors.computeIfAbsent(player, id -> new Collector(name));
        if (name != null && !name.equals(collector.name)) { collector.name = name; dirty = true; }
        int before = found(collector);
        if (collector.ids.size() >= MAX_IDS_PER_PLAYER || !collector.ids.add(entry(plant, stage))) {
            return new Recorded(false, null, before);
        }
        dirty = true;
        int after = before + 1;
        Rank old = Rank.of(before), now = Rank.of(after);
        return new Recorded(true, now != old ? now : null, after);
    }

    boolean has(UUID player, Plant plant, Stage stage) {
        Collector collector = collectors.get(player);
        return collector != null && collector.ids.contains(entry(plant, stage));
    }

    /** Known forms a player has found (ids from newer versions are kept but not counted). */
    int found(UUID player) {
        Collector collector = collectors.get(player);
        return collector == null ? 0 : found(collector);
    }

    private static int found(Collector collector) {
        int count = 0;
        for (String id : collector.ids) if (ENTRIES.contains(id)) count++;
        return count;
    }

    /** Forms found of one plant. */
    int found(UUID player, Plant plant) {
        int count = 0;
        for (Stage stage : plant.stages()) if (has(player, plant, stage)) count++;
        return count;
    }

    String name(UUID player) {
        Collector collector = collectors.get(player);
        return collector == null ? null : collector.name;
    }

    /** Player id by stored name (case-insensitive), or null. */
    UUID find(String name) {
        for (Map.Entry<UUID, Collector> entry : collectors.entrySet()) {
            if (entry.getValue().name != null && entry.getValue().name.equalsIgnoreCase(name)) return entry.getKey();
        }
        return null;
    }

    int collectors() { return collectors.size(); }

    /** Players with every form. */
    int keepers() {
        int count = 0;
        for (Collector collector : collectors.values()) if (found(collector) >= TOTAL) count++;
        return count;
    }

    /** Best collectors: most forms first, then by name. */
    List<Standing> top(int limit) {
        List<Standing> standings = new ArrayList<>();
        collectors.forEach((id, collector) -> {
            int found = found(collector);
            if (found > 0) standings.add(new Standing(id, collector.name == null ? id.toString() : collector.name, found));
        });
        standings.sort(Comparator.comparingInt(Standing::found).reversed()
                .thenComparing(Standing::name, String.CASE_INSENSITIVE_ORDER));
        return standings.size() <= limit ? standings : List.copyOf(standings.subList(0, limit));
    }

    boolean dirty() { return dirty; }
    void saved() { dirty = false; }

    /** The file content: {@code format}, then {@code players.<uuid>.name} and {@code .found} (list of form ids). */
    YamlConfiguration toYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", FORMAT);
        collectors.forEach((id, collector) -> {
            if (collector.name != null) yaml.set("players." + id + ".name", collector.name);
            yaml.set("players." + id + ".found", new ArrayList<>(collector.ids));
        });
        return yaml;
    }

    /** Reads a file written by {@link #toYaml}; malformed players are skipped, unknown form ids are kept. */
    static Herbarium fromYaml(YamlConfiguration yaml) {
        Herbarium herbarium = new Herbarium();
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) return herbarium;
        for (String key : players.getKeys(false)) {
            UUID id;
            try { id = UUID.fromString(key); } catch (IllegalArgumentException malformed) { continue; }
            ConfigurationSection section = players.getConfigurationSection(key);
            if (section == null) continue;
            Collector collector = new Collector(section.getString("name"));
            for (String entry : section.getStringList("found")) {
                if (collector.ids.size() >= MAX_IDS_PER_PLAYER) break;
                if (entry.length() <= 64) collector.ids.add(entry.toLowerCase(Locale.ROOT));
            }
            herbarium.collectors.put(id, collector);
        }
        return herbarium;
    }
}
