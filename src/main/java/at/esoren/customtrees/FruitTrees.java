package at.esoren.customtrees;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * Fruit trees ({@code fruit-trees.*}): right-click a sapling with a fruit (apple, sweet berries, glow berries, cocoa
 * beans) and the tree that grows from it bears that fruit. Ripe fruit hangs visibly under the leaves (item displays,
 * never saved with the world) and is picked by right-clicking the tree's leaves with an empty hand. Trees and marked
 * saplings are kept in {@code fruittrees.yml}. Server thread.
 */
final class FruitTrees implements Listener {
    static final int MAX_LEAVES = 64, MAX_TREES = 5000;

    /** One fruit tree: root log, fruit, sampled leaf positions, ripe fruit and when the next one grows. */
    static final class Tree {
        final UUID world; final int x, y, z; final Material fruit; final List<Long> leaves;
        int ripe; long nextGrowth;
        final List<UUID> displays = new ArrayList<>();
        Tree(UUID world, int x, int y, int z, Material fruit, List<Long> leaves, int ripe, long nextGrowth) {
            this.world = world; this.x = x; this.y = y; this.z = z; this.fruit = fruit; this.leaves = leaves; this.ripe = ripe; this.nextGrowth = nextGrowth;
        }
    }

    record Options(boolean enabled, long growMillis, int maxFruit, Set<Material> fruits) {
        static Options read(org.bukkit.configuration.file.FileConfiguration c) {
            Set<Material> fruits = new LinkedHashSet<>();
            for (String name : c.getStringList("fruit-trees.fruits")) { Material m = Material.matchMaterial(name); if (m != null && m.isItem()) fruits.add(m); }
            if (fruits.isEmpty()) fruits.addAll(List.of(Material.APPLE, Material.SWEET_BERRIES, Material.GLOW_BERRIES, Material.COCOA_BEANS));
            return new Options(c.getBoolean("fruit-trees.enabled", true), Math.clamp(c.getInt("fruit-trees.grow-minutes", 15), 1, 1440) * 60_000L,
                    Math.clamp(c.getInt("fruit-trees.max-fruit", 6), 1, 16), fruits);
        }
    }

    private final CustomTreesPlugin plugin;
    private final Path file;
    private final Map<UUID, Map<Long, Material>> markedWorlds = new HashMap<>();
    private final List<Tree> trees = new ArrayList<>();
    private final Map<Long, Tree> byLeaf = new HashMap<>();
    private Options options;
    private BukkitTask task;

    FruitTrees(CustomTreesPlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("fruittrees.yml");
        this.options = Options.read(plugin.getConfig());
        load();
    }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 200L); }

    void stop() {
        if (task != null) task.cancel();
        for (Tree t : trees) removeDisplays(t);
        save();
    }

    void apply(org.bukkit.configuration.file.FileConfiguration config) { options = Options.read(config); }

    String status() { return "fruit-trees: enabled=" + options.enabled() + ", trees=" + trees.size() + ", marked-saplings=" + markedWorlds.values().stream().mapToInt(Map::size).sum(); }

    static long key(int x, int y, int z) { return TimberScanner.pack(x, y, z); }

    private Map<Long, Material> marks(UUID world) { return markedWorlds.computeIfAbsent(world, w -> new HashMap<>()); }

    // ---- marking a sapling ----

    /**
     * Marking a sapling and picking fruit go through Core's stages: ACT, the feature consumes the click. Both also
     * receive cancelled events, as before, and decide by their own checks.
     */
    void handleInteract(org.bukkit.plugin.Plugin owner, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(owner, PlayerInteractEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.ACT, true, this::mark);
        stages.handle(owner, PlayerInteractEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.ACT, true, this::harvest);
    }
    /* Stage ACT, registered in handleInteract */
    public void mark(PlayerInteractEvent event) {
        if (!options.enabled() || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
        Block block = event.getClickedBlock();
        if (!Tag.SAPLINGS.isTagged(block.getType())) return;
        ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
        if (hand == null || !options.fruits().contains(hand.getType())) return;
        Player player = event.getPlayer();
        if (!player.hasPermission("customtrees.fruit")) return;
        event.setCancelled(true);
        Map<Long, Material> marks = marks(block.getWorld().getUID());
        long k = key(block.getX(), block.getY(), block.getZ());
        if (hand.getType().equals(marks.get(k))) { plugin.actionBar(player, "fruit.already"); return; }
        if (player.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        marks.put(k, hand.getType());
        block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(0.5, 0.6, 0.5), 8, 0.3, 0.3, 0.3, 0);
        plugin.actionBar(player, "fruit.marked");
        save();
    }

    // ---- growing ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void grow(StructureGrowEvent event) {
        if (!options.enabled()) return;
        Location at = event.getLocation();
        Map<Long, Material> marks = markedWorlds.get(at.getWorld().getUID());
        if (marks == null || marks.isEmpty()) return;
        Material fruit = null;
        int bx = at.getBlockX(), by = at.getBlockY(), bz = at.getBlockZ();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            Material m = marks.remove(key(bx + dx, by, bz + dz));
            if (m != null && fruit == null) fruit = m;
        }
        if (fruit == null) return;
        List<Long> leaves = new ArrayList<>();
        for (BlockState state : event.getBlocks())
            if (state.getType().name().endsWith("LEAVES")) leaves.add(key(state.getX(), state.getY(), state.getZ()));
        if (leaves.isEmpty() || trees.size() >= MAX_TREES) { save(); return; }
        Collections.shuffle(leaves);
        Tree tree = new Tree(at.getWorld().getUID(), bx, by, bz, fruit, List.copyOf(leaves.subList(0, Math.min(MAX_LEAVES, leaves.size()))),
                0, System.currentTimeMillis() + options.growMillis());
        trees.add(tree);
        index(tree);
        save();
        DebugMessages.low("[CustomTrees/Fruit] Fruit tree (" + fruit + ") at " + bx + "," + by + "," + bz);
    }

    private void index(Tree t) { for (long l : t.leaves) byLeaf.put(t.world.getLeastSignificantBits() * 31 + l, t); }
    private Tree treeAt(UUID world, long leaf) { return byLeaf.get(world.getLeastSignificantBits() * 31 + leaf); }

    // ---- ripening and display ----

    private void tick() {
        if (!options.enabled() || trees.isEmpty()) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Iterator<Tree> it = trees.iterator(); it.hasNext(); ) {
            Tree t = it.next();
            World world = Bukkit.getWorld(t.world);
            if (world == null || !world.isChunkLoaded(t.x >> 4, t.z >> 4)) { t.displays.clear(); continue; }
            Block root = world.getBlockAt(t.x, t.y, t.z);
            if (!root.getType().name().endsWith("_LOG") && !root.getType().name().endsWith("_WOOD") && !root.getType().name().endsWith("_STEM")) {
                removeDisplays(t);
                for (long l : t.leaves) byLeaf.remove(t.world.getLeastSignificantBits() * 31 + l);
                it.remove();
                changed = true;
                continue;
            }
            int grown = ripen(t.ripe, t.nextGrowth, now, options.growMillis(), options.maxFruit());
            if (grown != t.ripe) {
                t.ripe = grown;
                t.nextGrowth = now + options.growMillis();
                changed = true;
            }
            showDisplays(world, t);
        }
        if (changed) save();
    }

    /** Ripe fruit after a check at {@code now}: one more when the growth time has come, never above {@code max}. */
    static int ripen(int ripe, long nextGrowth, long now, long growMillis, int max) {
        if (ripe >= max || now < nextGrowth) return Math.min(ripe, max);
        return ripe + 1;
    }

    private void showDisplays(World world, Tree t) {
        t.displays.removeIf(id -> { Entity e = Bukkit.getEntity(id); return e == null || !e.isValid(); });
        while (t.displays.size() > t.ripe) { Entity e = Bukkit.getEntity(t.displays.removeLast()); if (e != null) e.remove(); }
        int i = t.displays.size();
        while (t.displays.size() < t.ripe && i < t.leaves.size()) {
            long leaf = t.leaves.get(i++);
            Block block = world.getBlockAt(TimberScanner.x(leaf), TimberScanner.y(leaf), TimberScanner.z(leaf));
            if (!block.getType().name().endsWith("LEAVES") || !block.getRelative(0, -1, 0).getType().isAir()) continue;
            try {
                ItemDisplay display = world.spawn(block.getLocation().add(0.5, -0.25, 0.5), ItemDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setItemStack(new ItemStack(t.fruit));
                    d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                });
                t.displays.add(display.getUniqueId());
            } catch (RuntimeException | LinkageError unsupported) { return; }
        }
    }

    private void removeDisplays(Tree t) {
        for (UUID id : t.displays) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        t.displays.clear();
    }

    // ---- harvesting ----

    /* Stage ACT, registered in handleInteract */
    public void harvest(PlayerInteractEvent event) {
        if (!options.enabled() || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
        Block block = event.getClickedBlock();
        if (!block.getType().name().endsWith("LEAVES")) return;
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand != null && !hand.getType().isAir()) return;
        Tree t = treeAt(block.getWorld().getUID(), key(block.getX(), block.getY(), block.getZ()));
        if (t == null) return;
        event.setCancelled(true);
        if (t.ripe <= 0) { plugin.actionBar(player, "fruit.unripe"); return; }
        int amount = t.ripe;
        t.ripe = 0;
        t.nextGrowth = System.currentTimeMillis() + options.growMillis();
        removeDisplays(t);
        for (ItemStack rest : player.getInventory().addItem(new ItemStack(t.fruit, amount)).values())
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        player.playSound(player.getLocation(), "minecraft:block.sweet_berry_bush.pick_berries", 0.8f, 1.1f);
        plugin.actionBar(player, "fruit.harvested", "amount", amount);
        save();
    }

    // ---- storage ----

    private void load() {
        if (!Files.exists(file)) return;
        YamlConfiguration yaml = YamlCheck.loadData(file, java.util.logging.Logger.getLogger("CustomTrees"));
        for (Map<?, ?> m : yaml.getMapList("trees")) {
            try {
                Material fruit = Material.matchMaterial(String.valueOf(m.get("fruit")));
                List<Long> leaves = new ArrayList<>();
                if (m.get("leaves") instanceof List<?> list) for (Object o : list) leaves.add(Long.parseLong(String.valueOf(o)));
                if (fruit == null || leaves.isEmpty()) continue;
                Tree t = new Tree(UUID.fromString(String.valueOf(m.get("world"))), num(m, "x"), num(m, "y"), num(m, "z"), fruit, List.copyOf(leaves),
                        num(m, "ripe"), Long.parseLong(String.valueOf(m.get("next"))));
                trees.add(t);
                index(t);
            } catch (RuntimeException skipped) { DebugMessages.mid("[CustomTrees/Fruit] Skipped a broken fruit tree: " + skipped); }
        }
        for (Map<?, ?> m : yaml.getMapList("marked")) {
            try {
                Material fruit = Material.matchMaterial(String.valueOf(m.get("fruit")));
                if (fruit != null) marks(UUID.fromString(String.valueOf(m.get("world")))).put(Long.parseLong(String.valueOf(m.get("at"))), fruit);
            } catch (RuntimeException skipped) { /* broken mark */ }
        }
    }

    private static int num(Map<?, ?> m, String k) { return Integer.parseInt(String.valueOf(m.get(k))); }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Fruit trees and marked saplings (fruit-trees in config.yml). Written by CustomTrees."));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Tree t : trees) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", t.world.toString()); m.put("x", t.x); m.put("y", t.y); m.put("z", t.z);
            m.put("fruit", t.fruit.name()); m.put("ripe", t.ripe); m.put("next", t.nextGrowth);
            m.put("leaves", t.leaves.stream().map(String::valueOf).toList());
            list.add(m);
        }
        yaml.set("trees", list);
        List<Map<String, Object>> marks = new ArrayList<>();
        markedWorlds.forEach((world, map) -> map.forEach((at, fruit) -> marks.add(Map.of("world", world.toString(), "at", String.valueOf(at), "fruit", fruit.name()))));
        yaml.set("marked", marks);
        try { Files.createDirectories(file.getParent()); plugin.core().data().files().write(file, yaml); }
        catch (IOException | RuntimeException failure) { DebugMessages.high("[CustomTrees/Fruit] fruittrees.yml not saved: " + failure); }
    }
}
