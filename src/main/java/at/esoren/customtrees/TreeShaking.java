package at.esoren.customtrees;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.loot.LootResult;
import Vystorm.vystorm_core.loot.LootRoll;
import Vystorm.vystorm_core.loot.LootTable;
import Vystorm.vystorm_core.loot.LootInterface;
import Vystorm.vystorm_core.loot.RollContext;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Right-click a trunk with an empty hand to shake the tree: a few apples, sticks, saplings or the species' own fruit
 * fall out of its crown. Bigger crowns give more; each tree rests a while afterwards. Works for every tree with leaves.
 */
final class TreeShaking {
    record Loot(Material item, double weight) {}
    enum Species {
        OAK(Material.OAK_LEAVES, Material.OAK_SAPLING, new Loot(Material.APPLE, 35)),
        DARK_OAK(Material.DARK_OAK_LEAVES, Material.DARK_OAK_SAPLING, new Loot(Material.APPLE, 40)),
        BIRCH(Material.BIRCH_LEAVES, Material.BIRCH_SAPLING, new Loot(Material.STICK, 10)),
        SPRUCE(Material.SPRUCE_LEAVES, Material.SPRUCE_SAPLING, new Loot(Material.SWEET_BERRIES, 20)),
        JUNGLE(Material.JUNGLE_LEAVES, Material.JUNGLE_SAPLING, new Loot(Material.COCOA_BEANS, 35)),
        ACACIA(Material.ACACIA_LEAVES, Material.ACACIA_SAPLING, new Loot(Material.STICK, 10)),
        CHERRY(Material.CHERRY_LEAVES, Material.CHERRY_SAPLING, new Loot(Material.PINK_PETALS, 45)),
        MANGROVE(Material.MANGROVE_LEAVES, Material.MANGROVE_PROPAGULE, new Loot(Material.MANGROVE_PROPAGULE, 20)),
        PALE_OAK(PaperCompat.material("PALE_OAK_LEAVES"), PaperCompat.material("PALE_OAK_SAPLING"), new Loot(PaperCompat.material("PALE_HANGING_MOSS"), 30));

        final Material leaves, sapling;
        final Loot special;
        Species(Material leaves, Material sapling, Loot special) { this.leaves = leaves; this.sapling = sapling; this.special = special; }

        /** Loot table of one roll: species item, sticks, sapling, a little nothing, and a rare golden apple for apple trees. */
        List<Loot> table() { return table(true); }

        /** Same table; without {@code goldenApples} apple trees never drop a golden apple ({@code shaking.golden-apples}). */
        List<Loot> table(boolean goldenApples) {
            List<Loot> table = new ArrayList<>(List.of(special, new Loot(Material.STICK, 35), new Loot(sapling, 12), new Loot(Material.AIR, 15)));
            if (goldenApples && special.item() == Material.APPLE) table.add(new Loot(Material.GOLDEN_APPLE, 0.6));
            return table;
        }

        static Species of(Material log) {
            String name = log.name();
            for (Species species : values()) {
                String prefix = species.name() + "_";
                if ((name.startsWith(prefix) || name.startsWith("STRIPPED_" + prefix))
                        && (name.endsWith("_LOG") || name.endsWith("_WOOD"))) return species;
            }
            return null;
        }
    }
    /** @param lootTable {@code shaking.loot-table}: Core loot table or category id; blank = {@value #CATEGORY} */
    record Options(boolean enabled, long cooldownMillis, int maxRolls, int minLeaves, boolean goldenApples, String lootTable) {
        Options {
            lootTable = lootTable == null ? "" : lootTable.trim();
        }
        static Options read(FileConfiguration config) {
            return new Options(config.getBoolean("shaking.enabled", true),
                    Math.clamp(config.getLong("shaking.cooldown-seconds", 300), 0, 86400) * 1000L,
                    Math.clamp(config.getInt("shaking.max-rolls", 6), 1, 32),
                    Math.clamp(config.getInt("shaking.min-leaves", 8), 1, 1000),
                    config.getBoolean("shaking.golden-apples", true),
                    config.getString("shaking.loot-table", ""));
        }
    }

    // ---- Vystorm Core loot (Core 0.44.0, capability loot-rolls) ----

    /** Default category of a shake; each species' own table is its {@code self}. */
    static final String CATEGORY = "category:gathering_find";
    private static final List<String> GOLDEN_APPLE_REFS = List.of("minecraft:golden_apple", "golden_apple");

    /** Own table of a species: {@code customtrees:shaking/<species>}. */
    static String tableId(Species species) { return "customtrees:shaking/" + species.name().toLowerCase(Locale.ROOT); }

    /**
     * The species' shaking table (with the golden apple; {@code golden-apples: false} excludes it per roll) as a Core
     * table: one draw, the old weights converted to rarity ranks with the same odds, "nothing" as an empty draw.
     */
    static LootTable lootTable(Species species) {
        List<Loot> table = species.table(true);
        int[] ranks = LootTable.ranksFor(table.stream().mapToDouble(Loot::weight).toArray());
        List<LootTable.Entry> entries = new ArrayList<>();
        for (int i = 0; i < table.size(); i++) {
            Material item = table.get(i).item();
            String ref = item == Material.AIR ? "" : "minecraft:" + item.name().toLowerCase(Locale.ROOT);
            entries.add(new LootTable.Entry(ref, 1, 1, ranks[i], ranks[i]));
        }
        return new LootTable(1, 1, 1.0, entries);
    }

    /** All own tables, keyed by id. */
    static Map<String, LootTable> lootTables() {
        Map<String, LootTable> tables = new LinkedHashMap<>();
        for (Species species : Species.values()) if (species.sapling != null) tables.put(tableId(species), lootTable(species));
        return tables;
    }

    /** Registers the own shaking tables in Core (enable). */
    static void registerLoot(LootInterface loot, org.bukkit.plugin.Plugin plugin) {
        try { loot.replace(plugin, lootTables()); }
        catch (RuntimeException failure) { DebugMessages.high("[CustomTrees/Shaking] Loot tables not registered: " + failure); }
    }

    /** Table or category a shake rolls: {@code shaking.loot-table} when registered, else {@value #CATEGORY}. */
    static String lootSource(LootInterface loot, Options options) { return loot.choose(options.lootTable(), CATEGORY); }

    /** One Core roll of a shake: {@code rolls} draws of the source with the species table as self. */
    static LootRoll lootRoll(LootInterface loot, Options options, int rolls) {
        LootRoll roll = loot.rolls().table(lootSource(loot, options)).rolls(Math.clamp(rolls, 0, 27));
        if (!options.goldenApples()) roll.exclude(GOLDEN_APPLE_REFS);
        return roll;
    }

    private final Set<String> reported = new HashSet<>();

    private void report(String problem) {
        if (reported.size() < 256 && reported.add(problem)) DebugMessages.high("[CustomTrees/Shaking] " + problem);
    }

    /** The shaking line of {@code /customtrees status}: switches and what fell out of trees since the start. */
    static String statusLine(Options options, long shaken, long items, long goldenApples, int resting) {
        return String.format(Locale.ROOT, "CustomTrees shaking: enabled=%s, cooldown=%ds, max-rolls=%d, golden-apples=%s, shaken=%d, items=%d, golden-dropped=%d, resting-now=%d",
                options.enabled(), options.cooldownMillis() / 1000, options.maxRolls(), options.goldenApples(), shaken, items, goldenApples, resting);
    }

    private static final int TRUNK_LIMIT = 512, REACH = 7, CROWN_HEIGHT = 40;
    private final CustomTreesPlugin plugin;
    private final Map<String, Long> rested = new HashMap<>();
    private final Map<UUID, Long> playerPause = new HashMap<>();
    private long shaken, items, goldenDropped;

    TreeShaking(CustomTreesPlugin plugin) { this.plugin = plugin; }

    /** Status line with the counters since the start; server thread. */
    String status(Options options) {
        long now = System.currentTimeMillis();
        int resting = (int) rested.values().stream().filter(time -> time > now).count();
        return statusLine(options, shaken, items, goldenDropped, resting);
    }

    /** Number of loot rolls for a crown: one per 40 leaves, at least one, at most {@code max}. */
    static int rolls(int leaves, int max) { return Math.max(1, Math.min(max, leaves / 40)); }

    /** Weighted pick from a table with a random value in [0, 1). */
    static Material pick(List<Loot> table, double random) {
        double total = table.stream().mapToDouble(Loot::weight).sum(), target = random * total;
        for (Loot loot : table) {
            target -= loot.weight();
            if (target < 0) return loot.item();
        }
        return table.getLast().item();
    }

    /** Shaking goes through Core's stages: ACT, the feature acts on a click that nobody denied. */
    void handleInteract(org.bukkit.plugin.Plugin owner, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(owner, PlayerInteractEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.ACT, this::shake);
    }
    /* Stage ACT, registered in handleInteract */
    public void shake(PlayerInteractEvent event) {
        Options options = plugin.shakingOptions();
        if (options == null || !options.enabled() || event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Player player = event.getPlayer();
        if (player.isSneaking() || !player.getInventory().getItemInMainHand().getType().isAir()
                || !player.hasPermission("customtrees.shake")) return;
        Species species = Species.of(event.getClickedBlock().getType());
        if (species == null) return;
        long now = System.currentTimeMillis();
        if (now < playerPause.getOrDefault(player.getUniqueId(), 0L)) return;
        playerPause.put(player.getUniqueId(), now + 1000);
        Block base = trunkBase(event.getClickedBlock());
        String key = base.getWorld().getUID() + ":" + base.getX() + "," + base.getY() + "," + base.getZ();
        Long ready = rested.get(key);
        if (ready != null && now < ready) {
            plugin.actionBar(player, "shaking.resting", "minutes", Math.max(1, (ready - now + 59_999) / 60_000));
            return;
        }
        List<Block> leaves = crown(event.getClickedBlock(), species.leaves);
        if (leaves.size() < options.minLeaves()) { plugin.actionBar(player, "shaking.no-crown"); return; }
        rested.put(key, now + options.cooldownMillis());
        if (rested.size() > 4096) rested.values().removeIf(time -> time < now);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        World world = base.getWorld();
        int dropped = 0;
        int rolls = rolls(leaves.size(), options.maxRolls());
        // Vystorm Core loot: category:gathering_find (or shaking.loot-table) with the species table as self.
        if (!options.lootTable().isEmpty() && !plugin.core().loot().exists(options.lootTable()))
            report("shaking.loot-table '" + options.lootTable() + "' is unknown, using " + CATEGORY);
        LootResult loot = lootRoll(plugin.core().loot(), options, rolls)
                .context(RollContext.of(player).withSelf(tableId(species)).withSource(plugin.getName()))
                .generate();
        loot.problems().forEach(this::report);
        for (ItemStack stack : loot.items()) {
            Block from = leaves.get(random.nextInt(leaves.size()));
            world.dropItemNaturally(from.getLocation().add(.5, -.3, .5), stack);
            dropped += stack.getAmount();
            if (stack.getType() == Material.GOLDEN_APPLE) goldenDropped += stack.getAmount();
        }
        shaken++;
        items += dropped;
        for (int i = 0; i < Math.min(10, leaves.size()); i++) {
            Block leaf = leaves.get(random.nextInt(leaves.size()));
            world.spawnParticle(Particle.BLOCK, leaf.getLocation().add(.5, .5, .5), 8, .4, .4, .4, 0, leaf.getBlockData());
        }
        Location at = event.getClickedBlock().getLocation().add(.5, .5, .5);
        world.playSound(at, Sound.BLOCK_AZALEA_LEAVES_HIT, 1f, .8f);
        world.playSound(at, Sound.BLOCK_GRASS_BREAK, .6f, 1.4f);
        if (dropped == 0) plugin.actionBar(player, "shaking.nothing");
        player.swingMainHand();
        event.setCancelled(true);
    }

    /** Lowest log of the trunk under the clicked log (the tree's identity for the rest timer). */
    static Block trunkBase(Block log) {
        Block base = log;
        for (int i = 0; i < 64 && Species.of(base.getRelative(BlockFace.DOWN).getType()) != null; i++) base = base.getRelative(BlockFace.DOWN);
        return base;
    }

    /** Leaves of the species around the logs connected to the clicked one (bounded). */
    static List<Block> crown(Block clicked, Material leafType) {
        World world = clicked.getWorld();
        Set<Block> logs = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>(List.of(clicked));
        int minX = clicked.getX(), maxX = minX, minY = clicked.getY(), maxY = minY, minZ = clicked.getZ(), maxZ = minZ;
        while (!queue.isEmpty() && logs.size() < TRUNK_LIMIT) {
            Block log = queue.removeFirst();
            if (!logs.add(log)) continue;
            minX = Math.min(minX, log.getX()); maxX = Math.max(maxX, log.getX());
            minY = Math.min(minY, log.getY()); maxY = Math.max(maxY, log.getY());
            minZ = Math.min(minZ, log.getZ()); maxZ = Math.max(maxZ, log.getZ());
            for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                int x = log.getX() + dx, y = log.getY() + dy, z = log.getZ() + dz;
                if (y >= world.getMaxHeight() || y > clicked.getY() + CROWN_HEIGHT || !world.isChunkLoaded(x >> 4, z >> 4)) continue;
                Block next = world.getBlockAt(x, y, z);
                if (Species.of(next.getType()) != null && !logs.contains(next)) queue.add(next);
            }
        }
        List<Block> leaves = new ArrayList<>();
        int budget = 80_000;
        for (int x = minX - REACH; x <= maxX + REACH; x++) for (int z = minZ - REACH; z <= maxZ + REACH; z++) {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int y = Math.max(world.getMinHeight(), minY); y <= Math.min(world.getMaxHeight() - 1, maxY + 4); y++) {
                if (--budget < 0) return leaves;
                Block block = world.getBlockAt(x, y, z);
                if (block.getType() == leafType) leaves.add(block);
            }
        }
        return leaves;
    }

    void forget(UUID player) { playerPause.remove(player); }
}
