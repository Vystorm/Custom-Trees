package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructureAccess;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructurePos;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeSpecies;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.region.RegionShape;

import at.esoren.customtrees.GeneratedTreeScanner.TreeBase;
import at.esoren.customtrees.GeneratedTreeScanner.TreeRemoval;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Replaces trees after a newly generated chunk is fully visible to Bukkit.
 * Iris places object trees too late for an ordinary BlockPopulator on some
 * generation paths, so this pass intentionally runs a few ticks afterwards.
 *
 * <p>Safety rules: the pass only changes blocks inside chunks that were
 * generated during this run and are still untouched ({@link FreshChunks}),
 * never inside protected Core regions, and never blocks another pass already
 * built. The base search runs on a chunk snapshot off the main thread; the
 * replacement jobs run on the main thread with a per-tick time budget.
 */
final class GeneratedTreeListener implements Listener {
    /** Main-thread time the replacement queue may use per tick. */
    static final long TICK_BUDGET_NANOS = 3_000_000L;
    /** Upper bound of queued replacement jobs; older jobs are dropped first. */
    static final int MAX_QUEUED_TREES = 4096;
    /** Horizontal reach of one replacement (removal plus the new crown). */
    private static final int PROTECTION_MARGIN = GeneratedTreeScanner.MAX_REMOVAL_RADIUS + 8;

    private final CustomTreesPlugin plugin;
    private final Supplier<ActiveTreeSettings> activeSupplier;
    private final Set<ChunkKey> pending = new HashSet<>();
    private final FreshChunks fresh = new FreshChunks();
    private final ArrayDeque<TreeJob> queue = new ArrayDeque<>();
    private final LongAdder inspectedChunks = new LongAdder();
    private final LongAdder replacedTrees = new LongAdder();
    private BukkitTask drainTask;

    GeneratedTreeListener(CustomTreesPlugin plugin, Supplier<TreeSettings> settingsSupplier) {
        this(plugin, () -> new ActiveTreeSettings(settingsSupplier.get(), BiomeGrowthProfiles.EMPTY), true);
    }

    private GeneratedTreeListener(CustomTreesPlugin plugin, Supplier<ActiveTreeSettings> activeSupplier,
            boolean activeSnapshot) {
        this.plugin = plugin;
        this.activeSupplier = activeSupplier;
    }

    static GeneratedTreeListener withActiveSettings(CustomTreesPlugin plugin,
            Supplier<ActiveTreeSettings> activeSupplier) {
        return new GeneratedTreeListener(plugin, activeSupplier, true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNewChunk(ChunkLoadEvent event) {
        ActiveTreeSettings active = activeSupplier.get();
        TreeSettings settings = active == null ? null : active.settings();
        World world = event.getWorld();
        if (!event.isNewChunk() || settings == null || !settings.replaceGeneratedTrees()
                || !settings.postProcessNewChunks()
                || !settings.enables(world.getName(), world.getEnvironment())
                || placesOwnTrees(world, plugin.getConfig().getStringList("settings.skip-generator-plugins"))) {
            return;
        }

        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        ChunkKey key = new ChunkKey(world.getUID(), chunkX, chunkZ);
        fresh.generated(key, plugin.core().compat().platform().getCurrentTick());
        if (!pending.add(key)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> process(key), settings.postProcessDelayTicks());
    }

    /**
     * Worlds whose chunk generator comes from one of {@code generatorPlugins} place their own trees (Vystorm
     * Strata builds world trees and 5×5 giants across many chunks); the pass would cut them apart.
     */
    static boolean placesOwnTrees(World world, Collection<String> generatorPlugins) {
        ChunkGenerator generator = world.getGenerator();
        if (generator == null || generatorPlugins.isEmpty()) {
            return false;
        }
        String owner;
        try {
            owner = JavaPlugin.getProvidingPlugin(generator.getClass()).getName();
        } catch (IllegalArgumentException | IllegalStateException notFromAPlugin) {
            return false;
        }
        return skipsGenerator(owner, generatorPlugins);
    }

    static boolean skipsGenerator(String owner, Collection<String> generatorPlugins) {
        for (String name : generatorPlugins) {
            if (name.equalsIgnoreCase(owner)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        fresh.forget(new ChunkKey(event.getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ()));
    }

    /**
     * Block break and place go through Core's stages, so Core decides when they run among all Vystorm plugins.
     * Both only mark the chunk as touched by a player: OBSERVE, the final outcome, never a cancelled event.
     */
    void handleBlockEvents(org.bukkit.plugin.Plugin owner, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(owner, BlockPlaceEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::onBlockPlace);
        stages.handle(owner, BlockBreakEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::onBlockBreak);
    }

    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void onBlockPlace(BlockPlaceEvent event) {
        touched(event.getBlock());
    }

    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void onBlockBreak(BlockBreakEvent event) {
        touched(event.getBlock());
    }

    private void touched(Block block) {
        fresh.forget(ChunkKey.ofBlock(block.getWorld().getUID(), block.getX(), block.getZ()));
    }

    long inspectedChunks() {
        return inspectedChunks.sum();
    }

    long replacedTrees() {
        return replacedTrees.sum();
    }

    int queuedTrees() {
        return queue.size();
    }

    private void process(ChunkKey key) {
        pending.remove(key);
        long tick = plugin.core().compat().platform().getCurrentTick();
        fresh.expire(tick);
        ActiveTreeSettings active = activeSupplier.get();
        TreeSettings settings = active == null ? null : active.settings();
        World world = Bukkit.getWorld(key.worldId());
        if (settings == null || world == null || !world.isChunkLoaded(key.chunkX(), key.chunkZ())
                || !settings.replaceGeneratedTrees() || !settings.postProcessNewChunks()
                || !settings.enables(world.getName(), world.getEnvironment())) {
            return;
        }
        Chunk chunk = world.getChunkAt(key.chunkX(), key.chunkZ());
        if (!fresh.isFresh(key, tick, chunk.getInhabitedTime())) {
            return;
        }

        inspectedChunks.increment();
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, false, false);
        long seed = world.getSeed();
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();
        int startX = key.chunkX() << 4;
        int startZ = key.chunkZ() << 4;
        double scanChance = active.biomeProfiles().isEmpty() ? settings.replacementChance() : 1.0;
        boolean deferredChance = !active.biomeProfiles().isEmpty();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<TreeBase> bases = GeneratedTreeScanner.findBases(new SnapshotReader(snapshot), seed,
                    startX, startZ, minHeight, maxHeight, settings.scanDepth(), scanChance);
            if (bases.isEmpty() || !plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> enqueue(key, seed, bases, deferredChance));
        });
    }

    private void enqueue(ChunkKey key, long worldSeed, List<TreeBase> bases, boolean deferredChance) {
        long chunkSeed = worldSeed
                ^ ((long) key.chunkX() * 341873128712L)
                ^ ((long) key.chunkZ() * 132897987541L);
        for (TreeBase base : bases) {
            StructurePos position = base.position();
            long seed = chunkSeed ^ FreshChunks.pack(position.x(), position.y(), position.z());
            queue.addLast(new TreeJob(key, base, seed, deferredChance));
        }
        while (queue.size() > MAX_QUEUED_TREES) {
            queue.removeFirst();
        }
        if (drainTask == null) {
            drainTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drain, 1L, 1L);
        }
    }

    private void drain() {
        long start = System.nanoTime();
        do {
            TreeJob job = queue.pollFirst();
            if (job == null) {
                break;
            }
            try {
                replace(job);
            } catch (RuntimeException failure) {
                DebugMessages.mid("[CustomTrees/GeneratedTreeListener] Tree replacement at "
                        + job.base().position() + " failed: " + failure);
            }
        } while (System.nanoTime() - start < TICK_BUDGET_NANOS);
        if (queue.isEmpty() && drainTask != null) {
            drainTask.cancel();
            drainTask = null;
        }
    }

    private void replace(TreeJob job) {
        ActiveTreeSettings active = activeSupplier.get();
        TreeSettings settings = active == null ? null : active.settings();
        World world = Bukkit.getWorld(job.chunk().worldId());
        if (settings == null || world == null || !settings.replaceGeneratedTrees()
                || !settings.postProcessNewChunks()
                || !settings.enables(world.getName(), world.getEnvironment())) {
            return;
        }
        StructurePos base = job.base().position();
        if (!world.isChunkLoaded(base.x() >> 4, base.z() >> 4)) return;
        BiomeGrowthProfiles.Profile profile = null;
        if (job.deferredChance() || !active.biomeProfiles().isEmpty()) {
            if (!active.biomeProfiles().isEmpty()) profile = active.biomeProfiles().forBiome(
                    PaperCompat.key(world.getBiome(base.x(), base.y(), base.z())));
            double replacementChance = profile != null && profile.replacementChance() != null
                    ? profile.replacementChance() : settings.replacementChance();
            if (GeneratedTreeScanner.replacementRoll(world.getSeed(), base.x(), base.y(), base.z())
                    >= replacementChance) return;
        }
        List<RegionShape> regions = ProtectedRegions.within(plugin.core().area().regions(), world.getUID(),
                base.x() - PROTECTION_MARGIN, base.z() - PROTECTION_MARGIN,
                base.x() + PROTECTION_MARGIN, base.z() + PROTECTION_MARGIN);
        PassAccess access = new PassAccess(world, plugin.core().compat().platform().getCurrentTick(), regions);
        if (!access.contains(base.x(), base.y(), base.z())
                || access.isPassBlock(base.x(), base.y(), base.z())
                || TreeSpecies.fromLog(access.getType(base.x(), base.y(), base.z())) != job.base().species()
                || !GeneratedTreeScanner.SOILS.contains(access.getType(base.x(), base.y() - 1, base.z()))) {
            return;
        }
        TreeRemoval removal = GeneratedTreeScanner.measure(access, job.base(), access::isPassBlock);
        GeneratedTreeScanner.remove(access, removal, access::isPassBlock);
        if (profile == null) {
            plugin.core().structureGenerator().tree(access, new Random(job.seed()), job.base().species())
                    .buildNormal(base);
        } else {
            CoreTreeProfiles.tree(plugin.core(), access, new Random(job.seed()), job.base().species(), profile)
                    .buildNormal(base);
        }
        if (job.base().species() == TreeSpecies.SPRUCE && !access.changedTreeBlocks.isEmpty()) {
            SpruceGround.decorate(access, base, 4, job.seed(), settings, world::getTemperature);
        }
        if (plugin.fireGuard() != null) plugin.fireGuard().markAll(access.changedTreeBlocks);
        replacedTrees.increment();
    }

    private record TreeJob(ChunkKey chunk, TreeBase base, long seed, boolean deferredChance) {
    }

    private record SnapshotReader(ChunkSnapshot snapshot) implements GeneratedTreeScanner.ChunkReader {
        @Override
        public Material type(int x, int y, int z) {
            return snapshot.getBlockType(x & 15, y, z & 15);
        }

        @Override
        public int highestY(int x, int z) {
            return snapshot.getHighestBlockYAt(x & 15, z & 15);
        }
    }

    /** World access restricted to fresh chunks outside protected regions; records what it builds. */
    private final class PassAccess implements StructureAccess {
        private final World world;
        private final UUID worldId;
        private final long tick;
        private final List<RegionShape> regions;
        private final List<org.bukkit.block.Block> changedTreeBlocks = new ArrayList<>();
        private final Map<Long, Boolean> freshChunks = new HashMap<>();

        private PassAccess(World world, long tick, List<RegionShape> regions) {
            this.world = world;
            this.worldId = world.getUID();
            this.tick = tick;
            this.regions = regions;
        }

        @Override
        public Material getType(int x, int y, int z) {
            return contains(x, y, z) ? world.getBlockAt(x, y, z).getType() : Material.BARRIER;
        }

        @Override
        public void setType(int x, int y, int z, Material material) {
            if (contains(x, y, z)) {
                world.getBlockAt(x, y, z).setType(material, false);
                record(x, y, z, material);
            }
        }

        @Override
        public void setBlockData(int x, int y, int z, BlockData data) {
            if (contains(x, y, z)) {
                world.getBlockAt(x, y, z).setBlockData(data, false);
                record(x, y, z, data.getMaterial());
            }
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return y >= world.getMinHeight() && y < world.getMaxHeight()
                    && isFreshChunk(x >> 4, z >> 4)
                    && (regions.isEmpty() || !ProtectedRegions.contains(regions, worldId, x, y, z));
        }

        boolean isPassBlock(int x, int y, int z) {
            return fresh.isPassBlock(ChunkKey.ofBlock(worldId, x, z), x, y, z);
        }

        private void record(int x, int y, int z, Material material) {
            if (TreeSpecies.isLog(material) || TreeSpecies.isLeaves(material)) changedTreeBlocks.add(world.getBlockAt(x, y, z));
            if (!material.isAir()) {
                fresh.recordPassBlock(ChunkKey.ofBlock(worldId, x, z), x, y, z);
            }
        }

        private boolean isFreshChunk(int chunkX, int chunkZ) {
            long packed = (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
            Boolean cached = freshChunks.get(packed);
            if (cached == null) {
                ChunkKey key = new ChunkKey(worldId, chunkX, chunkZ);
                cached = fresh.isTracked(key) && world.isChunkLoaded(chunkX, chunkZ)
                        && fresh.isFresh(key, tick, world.getChunkAt(chunkX, chunkZ).getInhabitedTime());
                freshChunks.put(packed, cached);
            }
            return cached;
        }
    }
}
