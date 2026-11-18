package at.esoren.customtrees;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Forest regrowth ({@code regrowth.*}): a felled wild tree that was not replanted by the player gets new saplings on
 * its old trunk spots after a delay – one or 2x2 for small trees, a 3x3 or 5x5 square for giants and colossi, so they
 * can grow back to their size. Pending plantings are kept in {@code regrowth.yml} and survive restarts; a spot that is
 * no longer free (someone built there) is dropped. Server thread.
 */
final class ForestRegrowth {
    /** One pending planting: world, packed trunk spots ({@link TimberScanner#pack}), sapling and when it is due. */
    record Pending(UUID world, List<Long> spots, Material sapling, long due) {}

    /** Settings from {@code regrowth} in config.yml. */
    record Options(boolean enabled, long delayMillis, double chance, List<String> worlds, boolean smallTrees) {
        static Options read(FileConfiguration config) {
            ConfigurationSection s = config.getConfigurationSection("regrowth");
            if (s == null) return new Options(true, 20 * 60_000L, 1.0, List.of(), true);
            int minutes = Math.clamp(s.getInt("delay-minutes", 20), 1, 7 * 24 * 60);
            List<String> worlds = s.getStringList("worlds").stream().map(w -> w.toLowerCase(Locale.ROOT)).toList();
            return new Options(s.getBoolean("enabled", true), minutes * 60_000L, Math.clamp(s.getDouble("chance", 1.0), 0.0, 1.0),
                    worlds, s.getBoolean("small-trees", true));
        }
        boolean allows(World world) { return enabled && (worlds.isEmpty() || worlds.contains(world.getName().toLowerCase(Locale.ROOT))); }
    }

    static final int MAX_PENDING = 5000;

    private final CustomTreesPlugin plugin;
    private final Path file;
    private final List<Pending> pending = new ArrayList<>();
    private Options options;
    private BukkitTask task;
    private long planted;

    ForestRegrowth(CustomTreesPlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("regrowth.yml");
        this.options = Options.read(plugin.getConfig());
        pending.addAll(load(file));
    }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200L, 200L); }

    void stop() {
        if (task != null) task.cancel();
        task = null;
        save();
    }

    void apply(FileConfiguration config) { options = Options.read(config); }

    String status() { return "regrowth: enabled=" + options.enabled() + ", pending=" + pending.size() + ", planted=" + planted; }

    /**
     * Remembers a felled tree. {@code size} is the size class, {@code logs} the packed log positions of the tree,
     * {@code log} its wood. Returns true when a planting was queued.
     */
    boolean felled(World world, TimberOptions.SizeClass size, Collection<Long> logs, Material log, boolean replantedByPlayer) {
        if (replantedByPlayer || world == null || !options.allows(world)) return false;
        if (size == TimberOptions.SizeClass.SMALL && !options.smallTrees()) return false;
        Material sapling = TimberReplant.sapling(log);
        List<Long> spots = spots(logs);
        if (sapling == null || spots.isEmpty() || pending.size() >= MAX_PENDING) return false;
        if (options.chance() < 1.0 && ThreadLocalRandom.current().nextDouble() >= options.chance()) return false;
        pending.add(new Pending(world.getUID(), spots, sapling, System.currentTimeMillis() + options.delayMillis()));
        save();
        return true;
    }

    /**
     * Where saplings go: the lowest log layer when it is a filled square of side 1, 2, 3 or 5 (single, 2x2, giant,
     * colossus); otherwise the single log nearest to the layer's centre for side up to 2, else nothing.
     */
    static List<Long> spots(Collection<Long> logs) {
        if (logs == null || logs.isEmpty()) return List.of();
        int minY = Integer.MAX_VALUE;
        for (long pos : logs) minY = Math.min(minY, TimberScanner.y(pos));
        List<Long> bottom = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long pos : logs) {
            if (TimberScanner.y(pos) != minY || bottom.contains(pos)) continue;
            bottom.add(pos);
            minX = Math.min(minX, TimberScanner.x(pos)); maxX = Math.max(maxX, TimberScanner.x(pos));
            minZ = Math.min(minZ, TimberScanner.z(pos)); maxZ = Math.max(maxZ, TimberScanner.z(pos));
        }
        int side = maxX - minX + 1;
        boolean square = side == maxZ - minZ + 1 && bottom.size() == side * side;
        if (square && (side == 1 || side == 2 || side == 3 || side == 5)) return List.copyOf(bottom);
        if (side > 5 || maxZ - minZ + 1 > 5) return List.of();
        double cx = (minX + maxX) / 2.0, cz = (minZ + maxZ) / 2.0;
        long best = bottom.getFirst();
        double bestDist = Double.MAX_VALUE;
        for (long pos : bottom) {
            double d = Math.pow(TimberScanner.x(pos) - cx, 2) + Math.pow(TimberScanner.z(pos) - cz, 2);
            if (d < bestDist) { bestDist = d; best = pos; }
        }
        return List.of(best);
    }

    private void tick() {
        if (pending.isEmpty() || !options.enabled()) return; // regrowth.enabled off: pending plantings wait
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.due() > now) continue;
            World world = Bukkit.getWorld(p.world());
            if (world == null) continue; // world not loaded right now: keep it
            Result result = plant(world, p);
            if (result == Result.WAIT) continue;
            it.remove();
            changed = true;
            if (result == Result.PLANTED) planted++;
        }
        if (changed) save();
    }

    enum Result { PLANTED, BLOCKED, WAIT }

    private Result plant(World world, Pending p) {
        BlockData data = p.sapling().createBlockData();
        List<Block> blocks = new ArrayList<>(p.spots().size());
        for (long pos : p.spots()) {
            int x = TimberScanner.x(pos), y = TimberScanner.y(pos), z = TimberScanner.z(pos);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return Result.WAIT;
            Block block = world.getBlockAt(x, y, z);
            if (!block.getType().isAir() || !data.isSupported(block)) return Result.BLOCKED;
            blocks.add(block);
        }
        for (Block block : blocks) block.setBlockData(data, true);
        world.playSound(blocks.getFirst().getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_GRASS_PLACE, 0.6f, 0.9f);
        DebugMessages.low("[CustomTrees/Regrowth] Planted " + blocks.size() + "x " + p.sapling() + " at " + blocks.getFirst().getLocation().toVector());
        return Result.PLANTED;
    }

    // ------------------------------------------------------------------ storage

    static List<Pending> load(Path file) {
        List<Pending> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        for (Object raw : yaml.getList("pending", List.of())) {
            if (!(raw instanceof java.util.Map<?, ?> map)) continue;
            try {
                UUID world = UUID.fromString(String.valueOf(map.get("world")));
                Material sapling = Material.matchMaterial(String.valueOf(map.get("sapling")));
                long due = Long.parseLong(String.valueOf(map.get("due")));
                List<Long> spots = new ArrayList<>();
                if (map.get("spots") instanceof List<?> list) for (Object s : list) spots.add(Long.parseLong(String.valueOf(s)));
                if (sapling != null && !spots.isEmpty() && spots.size() <= 25) out.add(new Pending(world, List.copyOf(spots), sapling, due));
            } catch (RuntimeException skipped) {
                DebugMessages.mid("[CustomTrees/Regrowth] Skipped a broken entry in regrowth.yml: " + skipped);
            }
        }
        return out;
    }

    static void write(AtomicFilesInterface files, Path file, List<Pending> pending) throws IOException {
        if (pending.isEmpty()) { Files.deleteIfExists(file); return; }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Felled trees that grow back (regrowth in config.yml). Written by CustomTrees."));
        List<java.util.Map<String, Object>> list = new ArrayList<>();
        for (Pending p : pending) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("world", p.world().toString());
            m.put("sapling", p.sapling().name());
            m.put("due", p.due());
            m.put("spots", p.spots().stream().map(String::valueOf).toList());
            list.add(m);
        }
        yaml.set("pending", list);
        Files.createDirectories(file.getParent());
        files.write(file, yaml);
    }

    private void save() {
        try { write(plugin.core().data().files(), file, pending); }
        catch (IOException | RuntimeException failure) { DebugMessages.high("[CustomTrees/Regrowth] regrowth.yml not saved: " + failure); }
    }
}
