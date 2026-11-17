package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeSpecies;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Fire protection for exact custom-tree logs and leaves, tracked by chunk-local coordinates. */
final class TreeFireGuard implements Listener {
    private static final int MAGIC = 0x43544647; // CTFG
    private static final int FORMAT_VERSION = 1;
    static final int MAX_MARKERS_PER_CHUNK = 16_384;

    /* Stable format-1 codes. Append only; never reorder or reuse a code without changing format version. */
    private static final List<Material> MATERIAL_CODES = java.util.Arrays.asList(
            Material.ACACIA_LOG, Material.ACACIA_LEAVES,
            Material.BIRCH_LOG, Material.BIRCH_LEAVES,
            Material.CHERRY_LOG, Material.CHERRY_LEAVES,
            Material.DARK_OAK_LOG, Material.DARK_OAK_LEAVES,
            Material.JUNGLE_LOG, Material.JUNGLE_LEAVES,
            Material.MANGROVE_LOG, Material.MANGROVE_LEAVES,
            Material.OAK_LOG, Material.OAK_LEAVES,
            PaperCompat.material("PALE_OAK_LOG"), PaperCompat.material("PALE_OAK_LEAVES"),
            Material.SPRUCE_LOG, Material.SPRUCE_LEAVES,
            Material.MUSHROOM_STEM, Material.RED_MUSHROOM_BLOCK, Material.BROWN_MUSHROOM_BLOCK,
            Material.CRIMSON_STEM, Material.NETHER_WART_BLOCK, Material.WARPED_STEM, Material.WARPED_WART_BLOCK);
    private static final Map<Material, Integer> MATERIAL_TO_CODE = codes();

    private final BooleanSupplier protectTrunks;
    private final BooleanSupplier protectCrowns;
    private final NamespacedKey markersKey;

    TreeFireGuard(org.bukkit.plugin.java.JavaPlugin plugin,
            BooleanSupplier protectTrunks, BooleanSupplier protectCrowns) {
        Objects.requireNonNull(plugin, "plugin");
        this.protectTrunks = Objects.requireNonNull(protectTrunks, "protectTrunks");
        this.protectCrowns = Objects.requireNonNull(protectCrowns, "protectCrowns");
        this.markersKey = new NamespacedKey("customtrees", "tree_fire_marks");
    }

    /** Marks only supported tree logs/leaves, and only in a chunk already loaded. */
    void mark(Block block) {
        if (!positionAvailable(block)) return;
        Material material = block.getType();
        Integer code = MATERIAL_TO_CODE.get(material);
        if (code == null) return;
        int position = pack(block.getX(), block.getY(), block.getZ());
        Chunk chunk = block.getChunk();
        PersistentDataContainer data = chunk.getPersistentDataContainer();
        ReadResult parsed = read(data);
        if (!parsed.valid()) return;
        List<Marker> markers = new ArrayList<>(parsed.markers());
        for (int i = 0; i < markers.size(); i++) {
            if (markers.get(i).position() == position) {
                if (markers.get(i).materialCode() == code) return;
                markers.set(i, new Marker(position, code));
                write(data, markers);
                return;
            }
        }
        if (markers.size() >= MAX_MARKERS_PER_CHUNK) return;
        markers.add(new Marker(position, code));
        write(data, markers);
    }

    /** Batch tree commits to avoid rewriting the entire chunk PDC array once per generated leaf. */
    void markAll(java.util.Collection<Block> blocks) {
        Map<Chunk, java.util.LinkedHashMap<Integer, Integer>> grouped = new java.util.LinkedHashMap<>();
        for (Block block : blocks) {
            if (!positionAvailable(block)) continue;
            Integer code = MATERIAL_TO_CODE.get(block.getType()); if (code == null) continue;
            grouped.computeIfAbsent(block.getChunk(), ignored -> new java.util.LinkedHashMap<>())
                    .put(pack(block.getX(), block.getY(), block.getZ()), code);
        }
        grouped.forEach((chunk, additions) -> {
            PersistentDataContainer data = chunk.getPersistentDataContainer(); ReadResult parsed = read(data);
            if (!parsed.valid()) return;
            var merged = new java.util.LinkedHashMap<Integer, Integer>();
            parsed.markers().forEach(m -> merged.put(m.position(), m.materialCode()));
            additions.forEach((position, code) -> { if (merged.containsKey(position) || merged.size() < MAX_MARKERS_PER_CHUNK) merged.put(position, code); });
            write(data, merged.entrySet().stream().map(e -> new Marker(e.getKey(), e.getValue())).toList());
        });
    }

    /** Packed chunk-local positions ({@link #pack}) of every marked tree block in this chunk; empty when none or invalid. */
    Set<Integer> markedPositions(Chunk chunk) {
        ReadResult parsed = read(chunk.getPersistentDataContainer());
        if (!parsed.valid() || parsed.markers().isEmpty()) return Set.of();
        Set<Integer> positions = new HashSet<>(parsed.markers().size() * 2);
        for (Marker marker : parsed.markers()) positions.add(marker.position());
        return positions;
    }

    /** Removes the markers of many blocks with one write per chunk (the timber removes whole trees). */
    void unmarkAll(java.util.Collection<Block> blocks) {
        Map<Chunk, Set<Integer>> grouped = new java.util.LinkedHashMap<>();
        for (Block block : blocks) {
            if (!positionAvailable(block)) continue;
            grouped.computeIfAbsent(block.getChunk(), ignored -> new HashSet<>()).add(pack(block.getX(), block.getY(), block.getZ()));
        }
        grouped.forEach((chunk, removed) -> {
            PersistentDataContainer data = chunk.getPersistentDataContainer();
            ReadResult parsed = read(data);
            if (!parsed.valid() || parsed.markers().isEmpty()) return;
            List<Marker> kept = parsed.markers().stream().filter(marker -> !removed.contains(marker.position())).toList();
            if (kept.size() == parsed.markers().size()) return;
            if (kept.isEmpty()) data.remove(markersKey);
            else write(data, kept);
        });
    }

    /** Removes the coordinate marker even if the block has already changed material. */
    void unmark(Block block) {
        if (!positionAvailable(block)) return;
        int position = pack(block.getX(), block.getY(), block.getZ());
        PersistentDataContainer data = block.getChunk().getPersistentDataContainer();
        ReadResult parsed = read(data);
        if (!parsed.valid()) return;
        List<Marker> markers = parsed.markers().stream()
                .filter(marker -> marker.position() != position).toList();
        if (markers.size() == parsed.markers().size()) return;
        if (markers.isEmpty()) data.remove(markersKey);
        else write(data, markers);
    }

    /**
     * Fire, explosions, pistons and entity block changes go through Core's stages too. Protected tree blocks do not
     * burn and do not pass fire on: PROTECT. The markers follow the final outcome in OBSERVE.
     */
    void handleWorldEvents(org.bukkit.plugin.Plugin owner, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(owner, BlockBurnEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.PROTECT, this::onBlockBurn);
        stages.handle(owner, BlockIgniteEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.PROTECT, this::onBlockIgnite);
        stages.handle(owner, BlockSpreadEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.PROTECT, this::onBlockSpread);
        stages.handle(owner, BlockBurnEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::onBlockBurned);
        stages.handle(owner, BlockExplodeEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::onBlockExplode);
        stages.handle(owner, EntityExplodeEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::onEntityExplode);
        stages.handle(owner, EntityChangeBlockEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::onEntityChangeBlock);
        stages.handle(owner, BlockPistonExtendEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::onPistonExtend);
        stages.handle(owner, BlockPistonRetractEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::onPistonRetract);
    }
    /* Stage PROTECT, registered in handleWorldEvents */
    public void onBlockBurn(BlockBurnEvent event) {
        if (protects(event.getBlock())) event.setCancelled(true);
    }

    /** Only a directly reported marked igniting block is attributed to the tree; no neighbor scans. */
    /* Stage PROTECT, registered in handleWorldEvents */
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (event.getIgnitingBlock() != null && protects(event.getIgnitingBlock())) event.setCancelled(true);
    }

    /** Fire spread is stopped only when the event itself names a marked tree block as its source. */
    /* Stage PROTECT, registered in handleWorldEvents */
    public void onBlockSpread(BlockSpreadEvent event) {
        if (protects(event.getSource())) event.setCancelled(true);
    }

    /* Stage OBSERVE, registered in handleWorldEvents */
    public void onBlockBurned(BlockBurnEvent event) { unmark(event.getBlock()); }

    /**
     * Block break and place go through Core's stages, so Core decides when they run among all Vystorm plugins.
     * Both only remove fire markers: OBSERVE, the final outcome, never a cancelled event.
     */
    void handleBlockEvents(org.bukkit.plugin.Plugin owner, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(owner, BlockBreakEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::onBroken);
        stages.handle(owner, BlockPlaceEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::onPlaced);
    }

    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void onBroken(BlockBreakEvent event) { if (!(event instanceof TimberBreakEvent)) unmark(event.getBlock()); }

    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void onPlaced(BlockPlaceEvent event) { unmark(event.getBlock()); }

    /* Stage OBSERVE, registered in handleWorldEvents */
    public void onBlockExplode(BlockExplodeEvent event) { event.blockList().forEach(this::unmark); }

    /* Stage OBSERVE, registered in handleWorldEvents */
    public void onEntityExplode(EntityExplodeEvent event) { event.blockList().forEach(this::unmark); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) { unmark(event.getBlock()); }

    /* Stage OBSERVE, registered in handleWorldEvents */
    public void onEntityChangeBlock(EntityChangeBlockEvent event) { unmark(event.getBlock()); }

    /** A piston move drops ownership at the source; it does not protect or tag the destination. */
    /* Stage OBSERVE, registered in handleWorldEvents */
    public void onPistonExtend(BlockPistonExtendEvent event) { event.getBlocks().forEach(this::unmark); }

    /* Stage OBSERVE, registered in handleWorldEvents */
    public void onPistonRetract(BlockPistonRetractEvent event) { event.getBlocks().forEach(this::unmark); }

    private boolean protects(Block block) {
        if (!positionAvailable(block)) return false;
        Material current = block.getType();
        Integer actualCode = MATERIAL_TO_CODE.get(current);
        if (actualCode == null) return false;
        ReadResult parsed = read(block.getChunk().getPersistentDataContainer());
        if (!parsed.valid()) return false;
        int position = pack(block.getX(), block.getY(), block.getZ());
        for (Marker marker : parsed.markers()) {
            if (marker.position() != position || marker.materialCode() != actualCode) continue;
            if (TreeSpecies.isLog(current) || current == Material.MUSHROOM_STEM || current == Material.CRIMSON_STEM
                    || current == Material.WARPED_STEM) return protectTrunks.getAsBoolean();
            if (MATERIAL_TO_CODE.containsKey(current)) return protectCrowns.getAsBoolean();
            return false;
        }
        return false;
    }

    private static boolean positionAvailable(Block block) {
        if (block == null) return false;
        World world = block.getWorld();
        return world != null && world.isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                && block.getY() >= -2048 && block.getY() <= 2047;
    }

    static int pack(int x, int y, int z) {
        if (y < -2048 || y > 2047) throw new IllegalArgumentException("Y coordinate outside fire marker format");
        return ((y + 2048) << 8) | (x & 15) | ((z & 15) << 4);
    }

    private ReadResult read(PersistentDataContainer data) {
        final int[] stored;
        try {
            if (!data.has(markersKey)) return ReadResult.EMPTY;
            if (!data.has(markersKey, PersistentDataType.INTEGER_ARRAY)) return ReadResult.INVALID;
            stored = data.get(markersKey, PersistentDataType.INTEGER_ARRAY);
        } catch (RuntimeException malformed) {
            return ReadResult.INVALID;
        }
        if (stored == null || stored.length < 3 || stored[0] != MAGIC || stored[1] != FORMAT_VERSION) return ReadResult.INVALID;
        int count = stored[2];
        if (count < 0 || count > MAX_MARKERS_PER_CHUNK || stored.length != 3 + count * 2) return ReadResult.INVALID;
        List<Marker> markers = new ArrayList<>(count);
        Set<Integer> positions = new HashSet<>();
        for (int i = 0; i < count; i++) {
            int position = stored[3 + i * 2];
            int materialCode = stored[4 + i * 2];
            if (position < 0 || (position >>> 20) != 0
                    || materialCode < 1 || materialCode > MATERIAL_CODES.size() || !positions.add(position)) {
                return ReadResult.INVALID;
            }
            markers.add(new Marker(position, materialCode));
        }
        return new ReadResult(true, List.copyOf(markers));
    }

    private void write(PersistentDataContainer data, List<Marker> markers) {
        int[] stored = new int[3 + markers.size() * 2];
        stored[0] = MAGIC;
        stored[1] = FORMAT_VERSION;
        stored[2] = markers.size();
        for (int i = 0; i < markers.size(); i++) {
            stored[3 + i * 2] = markers.get(i).position();
            stored[4 + i * 2] = markers.get(i).materialCode();
        }
        data.set(markersKey, PersistentDataType.INTEGER_ARRAY, stored);
    }

    private static Map<Material, Integer> codes() {
        java.util.LinkedHashMap<Material, Integer> result = new java.util.LinkedHashMap<>();
        for (int i = 0; i < MATERIAL_CODES.size(); i++) if (MATERIAL_CODES.get(i) != null) result.put(MATERIAL_CODES.get(i), i + 1);
        return Map.copyOf(result);
    }

    private record Marker(int position, int materialCode) { }
    private record ReadResult(boolean valid, List<Marker> markers) {
        private static final ReadResult EMPTY = new ReadResult(true, List.of());
        private static final ReadResult INVALID = new ReadResult(false, List.of());
    }
}
