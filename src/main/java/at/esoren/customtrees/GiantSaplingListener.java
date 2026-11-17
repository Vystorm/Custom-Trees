package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructureAccess;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructurePos;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeGenerator;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeSpecies;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.region.RegionShape;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

final class GiantSaplingListener implements Listener {
    static final int HEDGE_MIN_LENGTH = 4;
    static final int MAX_CONNECTED_SAPLINGS = 128;
    /**
     * CoreProtect logs our StructureGrowEvent itself and SmoothTimber adds "#"
     * removals five ticks later; protected trunks are logged after that.
     */
    static final long COREPROTECT_LOG_DELAY_TICKS = 20L;
    private static final int[][] HEDGE_NEIGHBORS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private final CustomTreesPlugin plugin;
    private final Supplier<ActiveTreeSettings> activeSupplier;
    private final CoreProtectBridge coreProtect;
    private final GlowstoneGuard glowstoneGuard = new GlowstoneGuard();
    private final CatalystUseLimiter catalystUseLimiter = new CatalystUseLimiter();
    private final Map<UUID, CoalQuote> coalQuotes = new HashMap<>();
    private record CoalQuote(TreeKey key, TreeSpecies species, CoalGrowthCosts.GrowthClass growthClass, int amount, long expiresNanos) {}
    private final Set<TreeKey> pending = new HashSet<>();
    private final Map<TreeKey, PendingPlayerGrowth> pendingPlayerGrowths = new HashMap<>();
    private final LongAdder regularTreesGrown = new LongAdder();
    private final LongAdder giantTreesGrown = new LongAdder();
    private final LongAdder colossalTreesGrown = new LongAdder();
    private final LongAdder shrubsGrown = new LongAdder();
    private final LongAdder hedgesGrown = new LongAdder();
    private final LongAdder glowstoneTreesGrown = new LongAdder();
    private final LongAdder coalTreesGrown = new LongAdder();
    private final LongAdder glowstoneBlocksPlaced = new LongAdder();
    private final Map<TreeSpecies, LongAdder> speciesTreesGrown = new EnumMap<>(TreeSpecies.class);
    /** Used only on the primary thread while one preview pattern is being resolved. */
    private boolean previewPatternUnverified;
    /** Particles and sound after a growth; replaceable in tests (Sound needs a running server). */
    GrowthEffects effects = this::playGrowthEffects;
    /** Set while CustomTrees fires its own StructureGrowEvent, so it does not react to it. */
    private boolean firingOwnGrowEvent;

    GiantSaplingListener(CustomTreesPlugin plugin, Supplier<TreeSettings> settingsSupplier) {
        this(plugin, () -> new ActiveTreeSettings(settingsSupplier.get(), BiomeGrowthProfiles.EMPTY), true);
    }

    private GiantSaplingListener(CustomTreesPlugin plugin, Supplier<ActiveTreeSettings> activeSupplier,
            boolean activeSnapshot) {
        this.plugin = plugin;
        this.activeSupplier = activeSupplier;
        this.coreProtect = new CoreProtectBridge(plugin);
        for (TreeSpecies species : TreeSpecies.values()) speciesTreesGrown.put(species, new LongAdder());
    }

    static GiantSaplingListener withActiveSettings(CustomTreesPlugin plugin,
            Supplier<ActiveTreeSettings> activeSupplier) {
        return new GiantSaplingListener(plugin, activeSupplier, true);
    }

    private ActiveTreeSettings currentActive() {
        return activeSupplier.get();
    }

    private TreeSettings currentSettings() {
        ActiveTreeSettings active = currentActive();
        return active == null ? null : active.settings();
    }

    /**
     * Growing with a catalyst and the preview go through Core's stages: ACT, the feature consumes a click that nobody
     * denied. Register it after the other click features of this plugin; it used to run behind them.
     */
    void handleInteract(org.bukkit.plugin.Plugin owner, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(owner, PlayerInteractEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.ACT, this::onGrowthCatalyst);
    }
    /* Stage ACT, registered in handleInteract */
    public void onGrowthCatalyst(PlayerInteractEvent event) {
        if (plugin.worldTrees() != null && plugin.worldTrees().interact(event)) return;
        TreeSettings settings = currentSettings();
        Block clicked = event.getClickedBlock();
        ItemStack item = event.getItem();
        Player player = event.getPlayer();
        if (settings != null && event.getAction() == Action.RIGHT_CLICK_BLOCK && clicked != null
                && event.getHand() == EquipmentSlot.HAND && player.isSneaking()
                && isEmpty(item) && isEmpty(player.getInventory().getItemInOffHand())) {
            TreeSpecies previewSpecies = TreeSpecies.fromSapling(clicked.getType());
            if (previewSpecies != null && player.hasPermission("customtrees.grow")) {
                if (settings.enables(clicked.getWorld().getName(), clicked.getWorld().getEnvironment())) {
                    preview(player, clicked, previewSpecies, settings);
                } else {
                    plugin.message(player, "growth.preview-disabled");
                }
                cancelCatalyst(event);
                return;
            }
        }
        GrowthMethod method = item == null ? null : GrowthMethod.from(item.getType());
        if (settings == null || event.getAction() != Action.RIGHT_CLICK_BLOCK || clicked == null || item == null
                || method == null || !player.hasPermission("customtrees.grow")) {
            return;
        }
        TreeSpecies species = TreeSpecies.fromSapling(clicked.getType());
        if (species == null
                || !settings.enables(clicked.getWorld().getName(), clicked.getWorld().getEnvironment())) {
            return;
        }
        if ((method == GrowthMethod.GLOWSTONE && !settings.glowstoneGrowth())
                || (method == GrowthMethod.COAL && !settings.coalGrowth())) {
            return;
        }

        ColossalGrid colossal = findColossalGrid(clicked, species);
        if (settings.colossalSaplings()
                && (method != GrowthMethod.BONEMEAL || settings.bonemealColossalGrowth())
                && colossal != null) {
            if (!canFit(colossal, settings)) {
                rejectNoRoom(event, player);
                return;
            }
            startPlayerGrowth(event, player, method, settings, colossalGrowth(colossal));
            return;
        }

        GiantGrid grid = findGrid(clicked, species);
        if (settings.giantSaplings()
                && (method != GrowthMethod.BONEMEAL || settings.bonemealGiantGrowth())
                && grid != null) {
            if (!canFit(grid, settings)) {
                rejectNoRoom(event, player);
                return;
            }
            startPlayerGrowth(event, player, method, settings, giantGrowth(grid));
            return;
        }

        if (settings.customPlayerGrownTrees()) {
            SaplingPlan square = findTwoByTwo(clicked, species);
            if (square != null) {
                if (!canFit(square)) {
                    rejectNoRoom(event, player);
                    return;
                }
                startPlayerGrowth(event, player, method, settings, customGrowth(square));
                return;
            }

            ConnectedPlan connected = settings.connectedShrubsAndHedges()
                    ? findConnectedPlan(clicked, species, settings.mixedHedges()) : null;
            if (connected != null) {
                if (!canFit(connected)) {
                    rejectNoRoom(event, player);
                    return;
                }
                startPlayerGrowth(event, player, method, settings, connectedGrowth(connected));
                return;
            }

            SaplingPlan plan = singlePlan(clicked, species);
            if (!canFit(plan)) {
                rejectNoRoom(event, player);
                return;
            }
            startPlayerGrowth(event, player, method, settings, customGrowth(plan));
        }
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getAmount() <= 0 || item.getType() == Material.AIR
                || item.getType() == Material.CAVE_AIR || item.getType() == Material.VOID_AIR;
    }

    private void preview(Player player, Block clicked, TreeSpecies species, TreeSettings settings) {
        previewPatternUnverified = false;
        PreviewPlan plan = previewPlan(clicked, species, settings);
        if (plan == null) {
            plugin.message(player, previewPatternUnverified
                    ? "growth.preview-pattern-unverified" : "growth.preview-no-pattern");
            return;
        }
        ActiveTreeSettings active = currentActive();
        if (active != null && !active.biomeProfiles().isEmpty()) {
            Growth candidate = plan.growth();
            int anchorX = (int) Math.floor(candidate.centerX());
            int anchorZ = (int) Math.floor(candidate.centerZ());
            BiomeGrowthProfiles.Profile profile = clicked.getWorld().isChunkLoaded(anchorX >> 4, anchorZ >> 4)
                    ? active.biomeProfiles().forBiome(PaperCompat.key(clicked.getWorld().getBiome(anchorX, candidate.y(), anchorZ))) : null;
            if (profile != null && plan.growth().profiled()) {
                int height = (int) Math.ceil(plan.clearanceHeight() * Math.max(1.0, profile.heightScale()));
                int radius = Math.min(40, (int) Math.ceil(plan.radius() * Math.max(1.0, profile.crownScale())));
                plan = new PreviewPlan(plan.growth(), plan.pattern(), height, radius,
                        previewClearance(plan.growth(), height));
            }
        }
        Growth previewGrowth = plan.growth();
        List<StructurePos> members = previewGrowth.saplings();
        boolean soil = members.stream().allMatch(position -> GeneratedTreeScanner.SOILS.contains(
                previewGrowth.world().getBlockAt(position.x(), position.y() - 1, position.z()).getType()));
        boolean protectedArea = intersectsProtectedPreview(plan);
        showPreviewParticles(player, plan);
        String speciesName = plugin.localized(player, "web.ui.species."
                + species.name().toLowerCase(Locale.ROOT));
        plugin.message(player, "growth.preview",
                "certainty", plugin.localized(player, previewPatternUnverified
                        ? "growth.preview-confidence-unverified" : "growth.preview-confidence-detected"),
                "formation", plugin.localized(player,
                "growth.preview-pattern." + plan.pattern()), "species", speciesName,
                "ground", plugin.localized(player, soil ? "growth.preview-ground-ok" : "growth.preview-ground-bad"),
                "space", plugin.localized(player, switch (plan.space()) {
                    case CLEAR -> "growth.preview-space-ok";
                    case BLOCKED -> "growth.preview-space-bad";
                    case UNLOADED -> "growth.preview-space-unloaded";
                }),
                "protection", plugin.localized(player,
                        protectedArea ? "growth.preview-protected" : "growth.preview-unprotected"));
        plugin.message(player, "growth.preview-note");
    }

    private PreviewPlan previewPlan(Block clicked, TreeSpecies species, TreeSettings settings) {
        World world = clicked.getWorld();
        ColossalGrid colossal = previewFindColossalGrid(clicked, species);
        if (settings.colossalSaplings() && colossal != null) {
            int height = settings.colossalMaxHeight()
                    + plugin.core().structureGenerator().heightBonus(species, 5);
            Growth growth = colossalGrowth(colossal);
            return new PreviewPlan(growth, "colossal", height, settings.colossalCanopyRadius(),
                    previewClearance(growth, height));
        }
        GiantGrid giant = previewFindGrid(clicked, species);
        if (settings.giantSaplings() && giant != null) {
            int height = settings.giantMaxHeight() + plugin.core().structureGenerator().heightBonus(species, 3);
            Growth growth = giantGrowth(giant);
            return new PreviewPlan(growth, "giant", height, settings.giantCanopyRadius(),
                    previewClearance(growth, height));
        }
        if (settings.customPlayerGrownTrees()) {
            SaplingPlan square = previewFindSquare(clicked, species, 2);
            if (square != null) {
                int height = species.maxHeight + 10 + plugin.core().structureGenerator().heightBonus(species, 2);
                Growth growth = customGrowth(square);
                return new PreviewPlan(growth, "square", height, Math.max(2, height / 2),
                        previewClearance(growth, height));
            }
            ConnectedPlan connected = settings.connectedShrubsAndHedges()
                    ? previewFindConnectedPlan(clicked, species, settings.mixedHedges()) : null;
            if (connected != null) {
                boolean hedge = connected.members().size() >= HEDGE_MIN_LENGTH;
                Growth growth = connectedGrowth(connected);
                return new PreviewPlan(growth, hedge ? "hedge" : "shrub", 5, 0,
                        previewClearance(growth, 5));
            }
            SaplingPlan single = singlePlan(clicked, species);
            int height = species.maxHeight + plugin.core().structureGenerator().heightBonus(species, 1);
            Growth growth = customGrowth(single);
            return new PreviewPlan(growth, "single", height, Math.max(2, height / 2),
                    previewClearance(growth, height));
        }
        return null;
    }

    private ColossalGrid previewFindColossalGrid(Block member, TreeSpecies species) {
        for (int offsetX = 0; offsetX < 5; offsetX++) {
            for (int offsetZ = 0; offsetZ < 5; offsetZ++) {
                int x = member.getX() - offsetX, z = member.getZ() - offsetZ, y = member.getY();
                if (previewSquareMatches(member.getWorld(), x, y, z, species, 5)) {
                    return new ColossalGrid(member.getWorld(), x, y, z, species);
                }
            }
        }
        return null;
    }

    private GiantGrid previewFindGrid(Block member, TreeSpecies species) {
        for (int offsetX = 0; offsetX < 3; offsetX++) {
            for (int offsetZ = 0; offsetZ < 3; offsetZ++) {
                int x = member.getX() - offsetX, z = member.getZ() - offsetZ, y = member.getY();
                if (previewSquareMatches(member.getWorld(), x, y, z, species, 3)) {
                    return new GiantGrid(member.getWorld(), x, y, z, species);
                }
            }
        }
        return null;
    }

    private SaplingPlan previewFindSquare(Block member, TreeSpecies species, int width) {
        for (int offsetX = 0; offsetX < width; offsetX++) {
            for (int offsetZ = 0; offsetZ < width; offsetZ++) {
                int x = member.getX() - offsetX, z = member.getZ() - offsetZ, y = member.getY();
                if (previewSquareMatches(member.getWorld(), x, y, z, species, width)) {
                    return new SaplingPlan(member.getWorld(), x, y, z, species, width);
                }
            }
        }
        return null;
    }

    private boolean previewSquareMatches(World world, int x, int y, int z, TreeSpecies species, int width) {
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < width; dz++) {
                if (!world.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)) {
                    previewPatternUnverified = true;
                    return false;
                }
                if (world.getBlockAt(x + dx, y, z + dz).getType() != species.sapling) return false;
            }
        }
        return true;
    }

    private ConnectedPlan previewFindConnectedPlan(Block member, TreeSpecies species, boolean allowMixed) {
        World world = member.getWorld();
        StructurePos first = new StructurePos(member.getX(), member.getY(), member.getZ());
        Set<StructurePos> members = new HashSet<>();
        Map<StructurePos, TreeSpecies> memberSpecies = new HashMap<>();
        ArrayDeque<StructurePos> queue = new ArrayDeque<>();
        members.add(first);
        memberSpecies.put(first, species);
        queue.add(first);
        while (!queue.isEmpty()) {
            StructurePos current = queue.removeFirst();
            for (int[] direction : HEDGE_NEIGHBORS) {
                StructurePos neighbor = current.add(direction[0], 0, direction[1]);
                if (members.contains(neighbor)) continue;
                if (!world.isChunkLoaded(neighbor.x() >> 4, neighbor.z() >> 4)) {
                    previewPatternUnverified = true;
                    continue;
                }
                TreeSpecies neighborSpecies = TreeSpecies.fromSapling(
                        world.getBlockAt(neighbor.x(), neighbor.y(), neighbor.z()).getType());
                if (neighborSpecies == null || (!allowMixed && neighborSpecies != species)) continue;
                if (members.size() >= MAX_CONNECTED_SAPLINGS) return null;
                members.add(neighbor);
                memberSpecies.put(neighbor, neighborSpecies);
                queue.addLast(neighbor);
            }
        }
        if (members.size() < 2) return null;
        List<StructurePos> ordered = members.stream()
                .sorted(Comparator.comparingInt(StructurePos::x).thenComparingInt(StructurePos::z)).toList();
        Map<StructurePos, TreeSpecies> snapshot = Map.copyOf(memberSpecies);
        boolean mixed = snapshot.values().stream().distinct().limit(2).count() > 1;
        if (mixed && ordered.size() < HEDGE_MIN_LENGTH) return previewFindConnectedPlan(member, species, false);
        return new ConnectedPlan(world, member.getY(), snapshot.get(ordered.getFirst()), ordered,
                snapshot, mixed, allowMixed);
    }

    private SpaceState previewClearance(Growth growth, int height) {
        World world = growth.world();
        int top = growth.y() + height;
        if (top >= world.getMaxHeight()) return SpaceState.BLOCKED;
        BufferedTreeAccess access = new BufferedTreeAccess(BufferedTreeAccess.BlockSource.of(world),
                Set.copyOf(growth.saplings()));
        for (StructurePos sapling : growth.saplings()) {
            for (int y = growth.y() + 1; y <= top; y++) {
                if (!world.isChunkLoaded(sapling.x() >> 4, sapling.z() >> 4)) return SpaceState.UNLOADED;
                if (!access.isWritable(sapling.x(), y, sapling.z())) {
                    return SpaceState.BLOCKED;
                }
            }
        }
        return SpaceState.CLEAR;
    }

    private boolean intersectsProtectedPreview(PreviewPlan plan) {
        Growth growth = plan.growth();
        int minX = growth.saplings().stream().mapToInt(StructurePos::x).min().orElse(growth.key().x());
        int maxX = growth.saplings().stream().mapToInt(StructurePos::x).max().orElse(growth.key().x());
        int minZ = growth.saplings().stream().mapToInt(StructurePos::z).min().orElse(growth.key().z());
        int maxZ = growth.saplings().stream().mapToInt(StructurePos::z).max().orElse(growth.key().z());
        minX -= plan.radius(); maxX += plan.radius(); minZ -= plan.radius(); maxZ += plan.radius();
        int bottom = growth.y();
        int top = Math.min(growth.world().getMaxHeight() - 1, growth.y() + plan.clearanceHeight());
        List<RegionShape> shapes = ProtectedRegions.within(plugin.core().area().regions(), growth.world().getUID(), minX, minZ, maxX, maxZ);
        for (RegionShape shape : shapes) {
            int y = Math.max(bottom, shape.minY());
            if (y > top || shape.maxY() < bottom) continue;
            int x0 = Math.max(minX, shape.minX()), x1 = Math.min(maxX, shape.maxX());
            int z0 = Math.max(minZ, shape.minZ()), z1 = Math.min(maxZ, shape.maxZ());
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (ProtectedRegions.contains(shapes, growth.world().getUID(), x, y, z)) return true;
                }
            }
        }
        return false;
    }

    /** Shows the sapling pattern and clearance box only to the requesting player; maximum 200 particles. */
    private void showPreviewParticles(Player player, PreviewPlan plan) {
        Growth growth = plan.growth();
        for (StructurePos sapling : growth.saplings()) {
            player.spawnParticle(Particle.HAPPY_VILLAGER,
                    sapling.x() + 0.5, sapling.y() + 0.15, sapling.z() + 0.5, 1, 0, 0, 0, 0);
        }
        int minX = growth.saplings().stream().mapToInt(StructurePos::x).min().orElse(growth.key().x()) - plan.radius();
        int maxX = growth.saplings().stream().mapToInt(StructurePos::x).max().orElse(growth.key().x()) + plan.radius();
        int minZ = growth.saplings().stream().mapToInt(StructurePos::z).min().orElse(growth.key().z()) - plan.radius();
        int maxZ = growth.saplings().stream().mapToInt(StructurePos::z).max().orElse(growth.key().z()) + plan.radius();
        int samples = Math.min(18, Math.max(1, plan.clearanceHeight()));
        int height = Math.max(1, plan.clearanceHeight());
        int[][] corners = {{minX, minZ}, {minX, maxZ}, {maxX, minZ}, {maxX, maxZ}};
        for (int[] corner : corners) {
            for (int sample = 0; sample < samples; sample++) {
                int y = growth.y() + 1 + (samples == 1 ? 0 : (height - 1) * sample / (samples - 1));
                if (y >= growth.world().getMaxHeight()) continue;
                player.spawnParticle(Particle.END_ROD, corner[0] + 0.5, y + 0.5, corner[1] + 0.5,
                        1, 0, 0, 0, 0);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNaturalGrowth(BlockGrowEvent event) {
        if (plugin.worldTrees() != null && plugin.worldTrees().interceptNatural(event.getBlock())) {
            event.setCancelled(true); return;
        }
        TreeSettings settings = currentSettings();
        Block block = event.getBlock();
        TreeSpecies species = TreeSpecies.fromSapling(block.getType());
        if (settings == null || species == null
                || !settings.enables(block.getWorld().getName(), block.getWorld().getEnvironment())) {
            return;
        }
        ColossalGrid colossal = findColossalGrid(block, species);
        if (settings.colossalSaplings() && settings.naturalColossalGrowth()
                && colossal != null && canFit(colossal, settings)) {
            event.setCancelled(true);
            schedule(colossalGrowth(colossal), GrowthMethod.DEFAULT, null, false, Refund.NONE);
            return;
        }
        GiantGrid grid = findGrid(block, species);
        if (settings.giantSaplings() && settings.naturalGiantGrowth()
                && grid != null && canFit(grid, settings)) {
            event.setCancelled(true);
            schedule(giantGrowth(grid), GrowthMethod.DEFAULT, null, false, Refund.NONE);
            return;
        }
        if (settings.customPlayerGrownTrees()) {
            SaplingPlan square = findTwoByTwo(block, species);
            if (square != null && canFit(square)) {
                event.setCancelled(true);
                schedule(customGrowth(square), GrowthMethod.DEFAULT, null, false, Refund.NONE);
                return;
            }
            ConnectedPlan connected = settings.connectedShrubsAndHedges()
                    ? findConnectedPlan(block, species, settings.mixedHedges()) : null;
            if (connected != null && canFit(connected)) {
                event.setCancelled(true);
                schedule(connectedGrowth(connected), GrowthMethod.DEFAULT, null, false, Refund.NONE);
                return;
            }
            SaplingPlan plan = singlePlan(block, species);
            if (canFit(plan)) {
                event.setCancelled(true);
                schedule(customGrowth(plan), GrowthMethod.DEFAULT, null, false, Refund.NONE);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVanillaTreeAttempt(StructureGrowEvent event) {
        if (plugin.worldTrees() != null) {
            if (plugin.worldTrees().ownsEvent()) return;
            if (plugin.worldTrees().interceptNatural(event.getLocation().getBlock())) {event.setCancelled(true);return;}
        }
        if (firingOwnGrowEvent) {
            return;
        }
        TreeSettings settings = currentSettings();
        Block block = event.getLocation().getBlock();
        TreeSpecies species = TreeSpecies.fromSapling(block.getType());
        Player player = event.getPlayer();
        if (settings == null || species == null
                || (player != null && !player.hasPermission("customtrees.grow"))
                || !settings.enables(block.getWorld().getName(), block.getWorld().getEnvironment())) {
            return;
        }
        boolean bonemeal = event.isFromBonemeal();

        ColossalGrid colossal = findColossalGrid(block, species);
        boolean colossalGrowthEnabled = settings.colossalSaplings()
                && (bonemeal ? settings.bonemealColossalGrowth() : settings.naturalColossalGrowth());
        if (colossalGrowthEnabled && colossal != null && canFit(colossal, settings)) {
            event.setCancelled(true);
            schedule(colossalGrowth(colossal), GrowthMethod.DEFAULT, player, bonemeal, Refund.NONE);
            return;
        }

        GiantGrid grid = findGrid(block, species);
        boolean giantGrowthEnabled = settings.giantSaplings()
                && (bonemeal ? settings.bonemealGiantGrowth() : settings.naturalGiantGrowth());
        if (giantGrowthEnabled && grid != null && canFit(grid, settings)) {
            event.setCancelled(true);
            schedule(giantGrowth(grid), GrowthMethod.DEFAULT, player, bonemeal, Refund.NONE);
            return;
        }

        if (!settings.customPlayerGrownTrees()) {
            return;
        }
        SaplingPlan square = findTwoByTwo(block, species);
        if (square != null && canFit(square)) {
            event.setCancelled(true);
            schedule(customGrowth(square), GrowthMethod.DEFAULT, player, bonemeal, Refund.NONE);
            return;
        }
        ConnectedPlan connected = settings.connectedShrubsAndHedges()
                ? findConnectedPlan(block, species, settings.mixedHedges()) : null;
        if (connected != null && canFit(connected)) {
            event.setCancelled(true);
            schedule(connectedGrowth(connected), GrowthMethod.DEFAULT, player, bonemeal, Refund.NONE);
            return;
        }
        SaplingPlan plan = singlePlan(block, species);
        if (plan != null && canFit(plan)) {
            event.setCancelled(true);
            schedule(customGrowth(plan), GrowthMethod.DEFAULT, player, bonemeal, Refund.NONE);
        }
    }

    private Growth giantGrowth(GiantGrid grid) {
        return new Growth(grid.world(), grid.species(), new TreeKey(grid.world().getUID(), grid.x(), grid.y(), grid.z()),
                square(grid.x(), grid.y(), grid.z(), 3), treeType(grid.species(), 3),
                grid.x() + 1.5, grid.y(), grid.z() + 1.5, 0L, 1L, true, true, CoalGrowthCosts.GrowthClass.GIANT,
                current -> current.giantSaplings()
                        && findGrid(grid.world().getBlockAt(grid.x(), grid.y(), grid.z()), grid.species()) != null
                        && canFit(grid, current),
                (access, builder, current) -> builder.buildGiant(new StructurePos(grid.x(), grid.y(), grid.z()),
                        current.giantMinHeight(), current.giantMaxHeight(), current.giantCanopyRadius()),
                giantTreesGrown::increment, 40, 0.65f);
    }

    private Growth colossalGrowth(ColossalGrid grid) {
        return new Growth(grid.world(), grid.species(), new TreeKey(grid.world().getUID(), grid.x(), grid.y(), grid.z()),
                square(grid.x(), grid.y(), grid.z(), 5), treeType(grid.species(), 5),
                grid.x() + 2.5, grid.y(), grid.z() + 2.5, 0L, 5L, true, true, CoalGrowthCosts.GrowthClass.COLOSSAL,
                current -> current.colossalSaplings()
                        && findColossalGrid(grid.world().getBlockAt(grid.x(), grid.y(), grid.z()), grid.species()) != null
                        && canFit(grid, current),
                (access, builder, current) -> builder.buildColossal(new StructurePos(grid.x(), grid.y(), grid.z()),
                        current.colossalMinHeight(), current.colossalMaxHeight(), current.colossalCanopyRadius()),
                colossalTreesGrown::increment, 70, 0.52f);
    }

    private Growth customGrowth(SaplingPlan plan) {
        double centerOffset = plan.width() / 2.0;
        return new Growth(plan.world(), plan.species(), new TreeKey(plan.world().getUID(), plan.x(), plan.y(), plan.z()),
                square(plan.x(), plan.y(), plan.z(), plan.width()), treeType(plan.species(), plan.width()),
                plan.x() + centerOffset, plan.y(), plan.z() + centerOffset, 0L, 1L, false, true, plan.width() == 2 ? CoalGrowthCosts.GrowthClass.MEDIUM : CoalGrowthCosts.GrowthClass.NORMAL,
                current -> current.customPlayerGrownTrees() && matches(plan) && canFit(plan),
                (access, builder, current) -> {
                    StructurePos base = new StructurePos(plan.x(), plan.y(), plan.z());
                    if (plan.width() == 2) {
                        builder.buildMedium(base);
                    } else {
                        builder.buildNormal(base);
                    }
                },
                regularTreesGrown::increment, plan.width() == 2 ? 28 : 18, 0.85f);
    }

    private Growth connectedGrowth(ConnectedPlan plan) {
        StructurePos anchor = plan.members().getFirst();
        boolean hedge = plan.members().size() >= HEDGE_MIN_LENGTH;
        double centerX = plan.members().stream().mapToInt(StructurePos::x).average().orElse(anchor.x()) + 0.5;
        double centerZ = plan.members().stream().mapToInt(StructurePos::z).average().orElse(anchor.z()) + 0.5;
        return new Growth(plan.world(), plan.species(), new TreeKey(plan.world().getUID(), anchor.x(), anchor.y(), anchor.z()),
                plan.members(), treeType(plan.species(), 1), centerX - 0.5, plan.y(), centerZ - 0.5,
                plan.members().size() * 42317861L, 1L, false, false, hedge ? CoalGrowthCosts.GrowthClass.HEDGE : CoalGrowthCosts.GrowthClass.NORMAL,
                current -> current.customPlayerGrownTrees() && current.connectedShrubsAndHedges()
                        && (!plan.mixed() || current.mixedHedges())
                        && matches(plan) && canFit(plan),
                (access, builder, current) -> {
                    if (!hedge || !plan.mixed()) {
                        builder.buildConnectedPlant(plan.members(), hedge);
                        return;
                    }
                    for (HedgeSegment segment : mixedHedgeSegments(plan)) {
                        plugin.core().structureGenerator().tree(access,
                                new Random(segment.seed()), segment.species())
                                .buildConnectedPlant(segment.members(), true);
                    }
                },
                hedge ? hedgesGrown::increment : shrubsGrown::increment,
                Math.min(60, 12 + plan.members().size() * 5), hedge ? 1.1f : 1.3f);
    }

    static List<HedgeSegment> mixedHedgeSegments(ConnectedPlan plan) {
        Set<StructurePos> unassigned = new HashSet<>(plan.members());
        List<HedgeSegment> segments = new ArrayList<>();
        while (!unassigned.isEmpty()) {
            StructurePos start = unassigned.stream()
                    .min(Comparator.comparingInt(StructurePos::x).thenComparingInt(StructurePos::z))
                    .orElseThrow();
            TreeSpecies species = plan.memberSpecies().get(start);
            ArrayDeque<StructurePos> pending = new ArrayDeque<>();
            List<StructurePos> members = new ArrayList<>();
            unassigned.remove(start);
            pending.add(start);
            while (!pending.isEmpty()) {
                StructurePos current = pending.removeFirst();
                members.add(current);
                for (int[] direction : HEDGE_NEIGHBORS) {
                    StructurePos neighbor = current.add(direction[0], 0, direction[1]);
                    if (unassigned.contains(neighbor) && plan.memberSpecies().get(neighbor) == species) {
                        unassigned.remove(neighbor);
                        pending.addLast(neighbor);
                    }
                }
            }
            members.sort(Comparator.comparingInt(StructurePos::x).thenComparingInt(StructurePos::z));
            StructurePos first = members.getFirst();
            long seed = ((long) first.x() << 32) ^ (first.z() & 0xffffffffL) ^ species.ordinal();
            segments.add(new HedgeSegment(species, List.copyOf(members), seed));
        }
        return List.copyOf(segments);
    }

    record HedgeSegment(TreeSpecies species, List<StructurePos> members, long seed) {
    }

    private static List<StructurePos> square(int x, int y, int z, int width) {
        List<StructurePos> positions = new ArrayList<>(width * width);
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < width; dz++) {
                positions.add(new StructurePos(x + dx, y, z + dz));
            }
        }
        return positions;
    }

    static TreeType treeType(TreeSpecies species, int width) {
        boolean large = width > 1;
        return switch (species.name()) {
            case "BIRCH" -> large ? TreeType.TALL_BIRCH : TreeType.BIRCH;
            case "SPRUCE" -> large ? TreeType.MEGA_REDWOOD : TreeType.REDWOOD;
            case "JUNGLE" -> large ? TreeType.JUNGLE : TreeType.SMALL_JUNGLE;
            case "ACACIA" -> TreeType.ACACIA;
            case "DARK_OAK" -> TreeType.DARK_OAK;
            case "MANGROVE" -> large ? TreeType.TALL_MANGROVE : TreeType.MANGROVE;
            case "CHERRY" -> TreeType.CHERRY;
            case "PALE_OAK" -> PaperCompat.treeType("PALE_OAK", TreeType.DARK_OAK);
            default -> large ? TreeType.BIG_TREE : TreeType.TREE;
        };
    }

    private void startPlayerGrowth(PlayerInteractEvent event, Player player, GrowthMethod method,
            TreeSettings settings, Growth growth) {
        if (pending.contains(growth.key())) {
            // The same formation is already growing: take nothing a second time.
            cancelCatalyst(event);
            return;
        }
        int cost = method == GrowthMethod.COAL ? settings.coalGrowthCosts().cost(growth.growthClass()) : 1;
        if (method == GrowthMethod.COAL && player.getGameMode() != GameMode.CREATIVE && cost > 1) {
            CoalQuote quote = coalQuotes.remove(player.getUniqueId());
            long now = System.nanoTime();
            if (quote == null || now - quote.expiresNanos() >= 0 || !quote.key().equals(growth.key())
                    || quote.species() != growth.species() || quote.growthClass() != growth.growthClass() || quote.amount() != cost) {
                cancelCatalyst(event);
                coalQuotes.put(player.getUniqueId(), new CoalQuote(growth.key(), growth.species(), growth.growthClass(), cost, now + 10_000_000_000L));
                plugin.message(player, "growth.coal-cost-preview", "coal", cost, "formation",
                        plugin.localized(player, "growth.coal-class." + growth.growthClass().name().toLowerCase(Locale.ROOT)));
                return;
            }
        } else coalQuotes.remove(player.getUniqueId());
        CatalystUseLimiter.Reservation reservation = null;
        CatalystUseLimiter.Catalyst catalyst = switch (method) {
            case GLOWSTONE -> CatalystUseLimiter.Catalyst.GLOWSTONE;
            case COAL -> CatalystUseLimiter.Catalyst.COAL;
            default -> null;
        };
        if (catalyst != null) {
            var reserved = catalystUseLimiter.reserve(player, catalyst, settings);
            if (reserved.isEmpty()) {
                cancelCatalyst(event);
                plugin.actionBar(player, "growth.catalyst-limited");
                return;
            }
            reservation = reserved.get();
        }
        Refund refund = prepareGrowth(event, player, method, settings, cost);
        if (refund != null) {
            schedule(growth, method, player, method == GrowthMethod.BONEMEAL, refund, reservation);
        } else if (reservation != null) {
            catalystUseLimiter.release(reservation);
        }
    }

    private void schedule(Growth growth, GrowthMethod method, Player player, boolean bonemeal, Refund refund) {
        schedule(growth, method, player, bonemeal, refund, null);
    }

    private void schedule(Growth growth, GrowthMethod method, Player player, boolean bonemeal, Refund refund,
            CatalystUseLimiter.Reservation reservation) {
        if (!pending.add(growth.key())) {
            refund(refund);
            if (reservation != null) catalystUseLimiter.release(reservation);
            return;
        }
        PendingPlayerGrowth attempt = new PendingPlayerGrowth(player, reservation, refund);
        pendingPlayerGrowths.put(growth.key(), attempt);
        Bukkit.getScheduler().runTask(plugin, () -> {
            pending.remove(growth.key());
            pendingPlayerGrowths.remove(growth.key());
            ActiveTreeSettings active = currentActive();
            TreeSettings current = active == null ? null : active.settings();
            boolean grown = false;
            try {
                grown = !attempt.aborted && current != null
                        && current.enables(growth.world().getName(), growth.world().getEnvironment())
                        && (reservation == null || catalystUseLimiter.canContinue(player, reservation, current))
                        && (method != GrowthMethod.COAL || refund.item() == null
                            || current.coalGrowthCosts().cost(growth.growthClass()) == refund.amount())
                        && growth.stillValid().test(current)
                        && grow(growth, method, player, bonemeal, active);
                if (grown && reservation != null) {
                    catalystUseLimiter.commit(player, reservation, current);
                }
            } finally {
                if (!grown) {
                    if (reservation != null) catalystUseLimiter.release(reservation);
                    if (!attempt.refunded) refund(refund);
                }
            }
        });
    }

    /**
     * Plans the tree without touching the world, lets protection plugins
     * filter or cancel it through a StructureGrowEvent, then applies what is
     * left. Returns false when nothing was grown.
     */
    private boolean grow(Growth growth, GrowthMethod method, Player player, boolean bonemeal, ActiveTreeSettings active) {
        TreeSettings current = active.settings();
        World world = growth.world();
        GrowthPlanting.Planted selected = null;
        if (plugin.plantings() != null) for (StructurePos pos : growth.saplings()) {
            if (!world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) return false;
            GrowthPlanting.Planted tag = plugin.plantings().at(world.getBlockAt(pos.x(), pos.y(), pos.z()));
            if (tag == null) continue;
            if (tag.spec().species() != growth.species()
                    || !tag.spec().growthClass().name().equals(growth.growthClass().name())
                    || (selected != null && !selected.equals(tag))) {
                if (player != null) plugin.message(player, "garden.pattern-mismatch");
                return false;
            }
            selected = tag;
        }
        BufferedTreeAccess plan = new BufferedTreeAccess(BufferedTreeAccess.BlockSource.of(world),
                Set.copyOf(growth.saplings()));
        for (StructurePos sapling : growth.saplings()) {
            plan.setType(sapling.x(), sapling.y(), sapling.z(), Material.AIR);
        }
        StructurePos anchor = new StructurePos((int) Math.floor(growth.centerX()), growth.y(),
                (int) Math.floor(growth.centerZ()));
        long seed = world.getSeed()
                ^ (anchor.x() * 341873128712L)
                ^ (anchor.z() * 132897987541L)
                ^ growth.seedSalt()
                ^ (world.getGameTime() * growth.timeFactor());
        Random random = new Random(seed);
        BiomeGrowthProfiles.Profile profile = growth.profiled() && !active.biomeProfiles().isEmpty()
                ? active.biomeProfiles().forBiome(
                PaperCompat.key(world.getBiome(anchor.x(), anchor.y(), anchor.z()))) : null;
        if (selected != null) profile = new BiomeGrowthProfiles.Profile(1.0, 1.0,
                BiomeGrowthProfiles.CrownForm.valueOf(selected.spec().crownForm().name()), null);
        TreeGenerator builder = profile == null
                ? plugin.core().structureGenerator().tree(plan, random, growth.species())
                : CoreTreeProfiles.tree(plugin.core(), plan, random, growth.species(), profile);
        growth.build().build(plan, builder, current);

        if (growth.species() == TreeSpecies.SPRUCE
                && growth.growthClass() != CoalGrowthCosts.GrowthClass.NORMAL
                && growth.growthClass() != CoalGrowthCosts.GrowthClass.HEDGE
                && plan.changes().values().stream().anyMatch(change -> change.material() == Material.SPRUCE_LOG)) {
            int radius = switch (growth.growthClass()) {
                case COLOSSAL -> 8;
                case GIANT -> 6;
                default -> 4;
            };
            SpruceGround.decorate(plan, anchor, radius, seed, current, world::getTemperature);
        }

        List<BlockState> states = new ArrayList<>();
        for (Map.Entry<StructurePos, BufferedTreeAccess.Planned> entry : plan.changes().entrySet()) {
            StructurePos position = entry.getKey();
            BufferedTreeAccess.Planned change = entry.getValue();
            if (change.data() == null && change.material() == plan.original(position)) {
                continue;
            }
            BlockState state = world.getBlockAt(position.x(), position.y(), position.z()).getState();
            if (change.data() != null) {
                state.setBlockData(change.data());
            } else {
                state.setType(change.material());
            }
            states.add(state);
        }
        if (states.isEmpty()) {
            return false;
        }
        StructurePos origin = growth.saplings().getFirst();
        StructureGrowEvent event = new StructureGrowEvent(new Location(world, origin.x(), origin.y(), origin.z()),
                growth.treeType(), bonemeal, player, states);
        firingOwnGrowEvent = true;
        try {
            Bukkit.getPluginManager().callEvent(event);
        } finally {
            firingOwnGrowEvent = false;
        }
        if (event.isCancelled() || event.getBlocks().isEmpty()) {
            if (player != null) {
                plugin.actionBar(player, "growth.not-allowed");
            }
            return false;
        }

        TrackingWorldTreeAccess applied = new TrackingWorldTreeAccess(world);
        int committedBlocks = 0;
        List<Block> committedTreeBlocks = new ArrayList<>();
        for (BlockState state : event.getBlocks()) {
            if (!world.equals(state.getWorld()) || !world.isChunkLoaded(state.getX() >> 4, state.getZ() >> 4)) {
                continue;
            }
            StructurePos position = new StructurePos(state.getX(), state.getY(), state.getZ());
            try {
                if (state.update(true, false)) {
                    applied.markChanged(position);
                    committedTreeBlocks.add(world.getBlockAt(state.getX(), state.getY(), state.getZ()));
                    committedBlocks++;
                }
            } catch (RuntimeException | LinkageError failure) {
                DebugMessages.mid("[CustomTrees] A planned tree block could not be applied: " + failure);
            }
        }
        if (committedBlocks == 0) {
            if (player != null) plugin.actionBar(player, "growth.not-allowed");
            return false;
        }
        try {
            finishGrowth(world, growth.species(), applied, random, current, method, growth.protectedByDefault());
        } catch (RuntimeException | LinkageError failure) {
            // Blocks are already committed, so follow-up failures must not refund their catalyst or XP.
            DebugMessages.mid("[CustomTrees] Tree follow-up failed after placement: " + failure);
        }
        if (plugin.fireGuard() != null) plugin.fireGuard().markAll(committedTreeBlocks);
        if (plugin.plantings() != null) for (StructurePos pos : growth.saplings()) {
            if (world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)
                    && world.getBlockAt(pos.x(), pos.y(), pos.z()).getType() != growth.species().sapling)
                plugin.plantings().remove(world.getBlockAt(pos.x(), pos.y(), pos.z()));
        }
        if (selected != null && plugin.gardens() != null) plugin.gardens().register(selected.owner(), committedTreeBlocks);
        growth.counter().run();
        speciesTreesGrown.get(growth.species()).increment();
        if (player != null && plugin.herbarium() != null) {
            try {
                plugin.herbarium().grown(player, Herbarium.Plant.of(growth.species().sapling),
                        Herbarium.Stage.of(growth.growthClass()), method == GrowthMethod.GLOWSTONE);
            } catch (RuntimeException | LinkageError failure) {
                DebugMessages.mid("[CustomTrees] Herbarium update failed: " + failure);
            }
        }
        try {
            effects.play(world, growth.centerX(), growth.y(), growth.centerZ(), growth.particles(), growth.pitch());
        } catch (RuntimeException | LinkageError failure) {
            DebugMessages.mid("[CustomTrees] Growth effect failed after placement: " + failure);
        }
        return true;
    }

    GlowstoneGuard glowstoneGuard() {
        return glowstoneGuard;
    }

    long regularTreesGrown() {
        return regularTreesGrown.sum();
    }

    long giantTreesGrown() {
        return giantTreesGrown.sum();
    }

    long colossalTreesGrown() {
        return colossalTreesGrown.sum();
    }

    long coreProtectLoggedBlocks() {
        return coreProtect.loggedBlocks();
    }

    long shrubsGrown() {
        return shrubsGrown.sum();
    }

    long hedgesGrown() {
        return hedgesGrown.sum();
    }

    long glowstoneTreesGrown() {
        return glowstoneTreesGrown.sum();
    }

    long coalTreesGrown() {
        return coalTreesGrown.sum();
    }

    Map<String, Long> speciesTreesGrown() {
        Map<String, Long> snapshot = new java.util.LinkedHashMap<>();
        for (TreeSpecies species : TreeSpecies.values()) {
            snapshot.put(species.name().toLowerCase(Locale.ROOT), speciesTreesGrown.get(species).sum());
        }
        return Collections.unmodifiableMap(snapshot);
    }

    void clearPendingReservations() {
        coalQuotes.clear();
        for (PendingPlayerGrowth attempt : pendingPlayerGrowths.values()) {
            attempt.aborted = true;
            if (!attempt.refunded) { refund(attempt.refund); attempt.refunded = true; }
        }
        catalystUseLimiter.clearPending();
        pendingPlayerGrowths.clear();
        pending.clear();
    }

    /** Cancels scheduled player growth when the player disconnects before its next-tick commit. */
    void onPlayerQuit(Player player) {
        UUID playerId = player.getUniqueId();
        coalQuotes.remove(playerId);
        for (PendingPlayerGrowth attempt : pendingPlayerGrowths.values()) {
            if (attempt.player == null || !playerId.equals(attempt.player.getUniqueId())) continue;
            attempt.aborted = true;
            if (attempt.reservation != null) catalystUseLimiter.release(attempt.reservation);
            if (!attempt.refunded) {
                refund(attempt.refund);
                attempt.refunded = true;
            }
        }
    }

    long glowstoneBlocksPlaced() {
        return glowstoneBlocksPlaced.sum();
    }

    private void finishGrowth(World world, TreeSpecies species, TrackingWorldTreeAccess access,
            Random random, TreeSettings settings, GrowthMethod method,
            boolean protectedByDefault) {
        if (method == GrowthMethod.GLOWSTONE) {
            int placed = plugin.core().structureGenerator().decorateGlowstone(access,
                    Set.copyOf(access.changedPositions()), species, random,
                    settings.glowstoneLeavesPerBlock(), settings.glowstoneMaxBlocks(),
                    settings.glowstoneDarkLightLevel());
            glowstoneBlocksPlaced.add(placed);
            glowstoneGuard.track(world, access.glowstonePositions());
            glowstoneTreesGrown.increment();
        } else if (method == GrowthMethod.COAL) {
            coalTreesGrown.increment();
        }
        boolean protectLogs = method == GrowthMethod.GLOWSTONE
                || (method != GrowthMethod.COAL && protectedByDefault);
        List<StructurePos> toLog = new ArrayList<>(access.glowstonePositions());
        if (protectLogs) {
            for (StructurePos position : access.changedPositions()) {
                if (TreeSpecies.isLog(access.getType(position.x(), position.y(), position.z()))) {
                    toLog.add(position);
                }
            }
        }
        if (!toLog.isEmpty()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> coreProtect.logPlacements(world, toLog),
                    COREPROTECT_LOG_DELAY_TICKS);
        }
    }

    private void playGrowthEffects(World world, double x, int y, double z, int particles, float pitch) {
        plugin.core().particle().spawn(Particle.HAPPY_VILLAGER, new Location(world, x, y + 2.0, z),
                particles, 1.5, 2.0, 1.5, 0.05, null);
        world.playSound(new Location(world, x, y + 1.0, z),
                Sound.ITEM_BONE_MEAL_USE, 1.0f, pitch);
    }

    static SaplingPlan singlePlan(Block member, TreeSpecies species) {
        return new SaplingPlan(member.getWorld(), member.getX(), member.getY(), member.getZ(), species, 1);
    }

    static SaplingPlan findTwoByTwo(Block member, TreeSpecies species) {
        int y = member.getY();
        World world = member.getWorld();
        for (int offsetX = 0; offsetX < 2; offsetX++) {
            for (int offsetZ = 0; offsetZ < 2; offsetZ++) {
                int originX = member.getX() - offsetX;
                int originZ = member.getZ() - offsetZ;
                SaplingPlan plan = new SaplingPlan(world, originX, y, originZ, species, 2);
                if (matches(plan)) {
                    return plan;
                }
            }
        }
        return null;
    }

    private static boolean matches(SaplingPlan plan) {
        for (int dx = 0; dx < plan.width(); dx++) {
            for (int dz = 0; dz < plan.width(); dz++) {
                if (plan.world().getBlockAt(plan.x() + dx, plan.y(), plan.z() + dz).getType()
                        != plan.species().sapling) {
                    return false;
                }
            }
        }
        return true;
    }

    static ConnectedPlan findConnectedPlan(Block member, TreeSpecies species) {
        return findConnectedPlan(member, species, false);
    }

    static ConnectedPlan findConnectedPlan(Block member, TreeSpecies species, boolean allowMixed) {
        World world = member.getWorld();
        StructurePos first = new StructurePos(member.getX(), member.getY(), member.getZ());
        Set<StructurePos> members = new HashSet<>();
        Map<StructurePos, TreeSpecies> memberSpecies = new HashMap<>();
        ArrayDeque<StructurePos> pendingMembers = new ArrayDeque<>();
        members.add(first);
        memberSpecies.put(first, species);
        pendingMembers.add(first);
        while (!pendingMembers.isEmpty()) {
            StructurePos current = pendingMembers.removeFirst();
            for (int[] direction : HEDGE_NEIGHBORS) {
                StructurePos neighbor = current.add(direction[0], 0, direction[1]);
                if (!members.contains(neighbor) && world.isChunkLoaded(neighbor.x() >> 4, neighbor.z() >> 4)) {
                    TreeSpecies neighborSpecies = TreeSpecies.fromSapling(
                            world.getBlockAt(neighbor.x(), neighbor.y(), neighbor.z()).getType());
                    if (neighborSpecies == null || (!allowMixed && neighborSpecies != species)) continue;
                    if (members.size() >= MAX_CONNECTED_SAPLINGS) {
                        return null;
                    }
                    members.add(neighbor);
                    memberSpecies.put(neighbor, neighborSpecies);
                    pendingMembers.addLast(neighbor);
                }
            }
        }
        if (members.size() < 2) {
            return null;
        }
        List<StructurePos> ordered = members.stream()
                .sorted(Comparator.comparingInt(StructurePos::x).thenComparingInt(StructurePos::z))
                .toList();
        TreeSpecies anchorSpecies = memberSpecies.get(ordered.getFirst());
        Map<StructurePos, TreeSpecies> speciesSnapshot = Map.copyOf(memberSpecies);
        boolean isMixed = speciesSnapshot.values().stream().distinct().limit(2).count() > 1;
        return new ConnectedPlan(world, member.getY(), anchorSpecies, ordered,
                speciesSnapshot, isMixed, allowMixed);
    }

    static boolean matches(ConnectedPlan plan) {
        for (StructurePos position : plan.members()) {
            if (plan.world().getBlockAt(position.x(), position.y(), position.z()).getType()
                    != plan.memberSpecies().get(position).sapling) {
                return false;
            }
        }
        StructurePos anchor = plan.members().getFirst();
        ConnectedPlan current = findConnectedPlan(
                plan.world().getBlockAt(anchor.x(), anchor.y(), anchor.z()),
                plan.memberSpecies().get(anchor), plan.allowMixed());
        return current != null && current.members().equals(plan.members())
                && current.memberSpecies().equals(plan.memberSpecies());
    }

    boolean canFit(SaplingPlan plan) {
        World world = plan.world();
        int maximumHeight = plan.width() == 2 ? plan.species().maxHeight + 10 : plan.species().maxHeight;
        maximumHeight += plugin.core().structureGenerator().heightBonus(plan.species(), plan.width());
        int top = plan.y() + maximumHeight;
        if (top >= world.getMaxHeight()) {
            return false;
        }
        for (int dx = 0; dx < plan.width(); dx++) {
            for (int dz = 0; dz < plan.width(); dz++) {
                for (int y = plan.y() + 1; y <= top; y++) {
                    if (!isClearanceReplaceable(
                            world.getBlockAt(plan.x() + dx, y, plan.z() + dz).getType(), plan.species())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    static boolean canFit(ConnectedPlan plan) {
        if (plan.y() + 5 >= plan.world().getMaxHeight()) {
            return false;
        }
        for (StructurePos position : plan.members()) {
            for (int y = plan.y() + 1; y <= plan.y() + 5; y++) {
                    if (!isClearanceReplaceable(
                            plan.world().getBlockAt(position.x(), y, position.z()).getType(),
                            plan.memberSpecies().get(position))) {
                    return false;
                }
            }
        }
        return true;
    }

    static GiantGrid findGrid(Block member, TreeSpecies species) {
        int y = member.getY();
        World world = member.getWorld();
        for (int offsetX = 0; offsetX < 3; offsetX++) {
            for (int offsetZ = 0; offsetZ < 3; offsetZ++) {
                int originX = member.getX() - offsetX;
                int originZ = member.getZ() - offsetZ;
                boolean matches = true;
                for (int dx = 0; dx < 3 && matches; dx++) {
                    for (int dz = 0; dz < 3; dz++) {
                        if (world.getBlockAt(originX + dx, y, originZ + dz).getType() != species.sapling) {
                            matches = false;
                            break;
                        }
                    }
                }
                if (matches) {
                    return new GiantGrid(world, originX, y, originZ, species);
                }
            }
        }
        return null;
    }

    static ColossalGrid findColossalGrid(Block member, TreeSpecies species) {
        int y = member.getY();
        World world = member.getWorld();
        for (int offsetX = 0; offsetX < 5; offsetX++) {
            for (int offsetZ = 0; offsetZ < 5; offsetZ++) {
                int originX = member.getX() - offsetX;
                int originZ = member.getZ() - offsetZ;
                boolean matches = true;
                for (int dx = 0; dx < 5 && matches; dx++) {
                    for (int dz = 0; dz < 5; dz++) {
                        if (world.getBlockAt(originX + dx, y, originZ + dz).getType()
                                != species.sapling) {
                            matches = false;
                            break;
                        }
                    }
                }
                if (matches) {
                    return new ColossalGrid(world, originX, y, originZ, species);
                }
            }
        }
        return null;
    }

    boolean canFit(GiantGrid grid, TreeSettings settings) {
        World world = grid.world();
        int top = grid.y() + settings.giantMaxHeight() + plugin.core().structureGenerator().heightBonus(grid.species(), 3);
        if (top >= world.getMaxHeight()) {
            return false;
        }
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                for (int y = grid.y() + 1; y <= top; y++) {
                    if (!isClearanceReplaceable(
                            world.getBlockAt(grid.x() + dx, y, grid.z() + dz).getType(), grid.species())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    boolean canFit(ColossalGrid grid, TreeSettings settings) {
        World world = grid.world();
        int top = grid.y() + settings.colossalMaxHeight()
                + plugin.core().structureGenerator().heightBonus(grid.species(), 5);
        if (top >= world.getMaxHeight()) {
            return false;
        }
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                for (int y = grid.y() + 1; y <= top; y++) {
                    if (!isClearanceReplaceable(
                            world.getBlockAt(grid.x() + dx, y, grid.z() + dz).getType(),
                            grid.species())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    static boolean isClearanceReplaceable(Material material, TreeSpecies species) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR
                || TreeSpecies.isLog(material) || TreeSpecies.isLeaves(material)
                || material == Material.VINE
                || material == Material.SHORT_GRASS || material == Material.TALL_GRASS
                || material == Material.FERN || material == Material.LARGE_FERN
                || material == species.sapling;
    }


    /**
     * Takes the catalyst (and XP for glowstone) and returns what to give back
     * if the growth is aborted later, or null when the player cannot pay.
     */
    private Refund prepareGrowth(PlayerInteractEvent event, Player player,
            GrowthMethod method, TreeSettings settings, int amount) {
        cancelCatalyst(event);
        ItemStack held = event.getHand() == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        if (player.getGameMode() != GameMode.CREATIVE
                && (held == null || held.getAmount() < amount || GrowthMethod.from(held.getType()) != method)) {
            if (method == GrowthMethod.COAL) plugin.message(player, "growth.coal-missing", "coal", amount);
            return null;
        }
        int experience = 0;
        if (player.getGameMode() != GameMode.CREATIVE && method == GrowthMethod.GLOWSTONE) {
            if (!plugin.core().player().withdrawExperience(player, settings.glowstoneXpCost())) {
                plugin.actionBar(player, "growth.glowstone-missing-xp", "xp", settings.glowstoneXpCost());
                return null;
            }
            experience = settings.glowstoneXpCost();
            plugin.actionBar(player, "growth.glowstone-xp-used", "xp", settings.glowstoneXpCost());
        }
        ItemStack consumed = consumeCatalyst(player, event.getHand(), amount);
        if (method == GrowthMethod.COAL && amount > 1 && consumed != null) plugin.message(player, "growth.coal-cost-confirm", "coal", amount);
        return new Refund(player, consumed, experience, amount);
    }

    /** Removes the quoted number of catalysts from the hand; returns a snapshot of the stack before use, or null. */
    private ItemStack consumeCatalyst(Player player, EquipmentSlot hand, int amount) {
        if (player.getGameMode() == GameMode.CREATIVE || hand == null) {
            return null;
        }
        boolean mainHand = hand == EquipmentSlot.HAND;
        if (!mainHand && hand != EquipmentSlot.OFF_HAND) {
            return null;
        }
        ItemStack item = mainHand
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (item == null || item.getAmount() <= 0) {
            return null;
        }
        ItemStack beforeUse = item.clone();
        int slot = mainHand ? player.getInventory().getHeldItemSlot() : 40;
        item.setAmount(Math.max(0, beforeUse.getAmount() - amount));
        // CustomTrees cancelled the native use and consumed this catalyst itself.
        plugin.core().inventoryEvents().observeUse(player, slot, beforeUse, () -> true);
        return beforeUse;
    }

    /** Gives back catalyst and XP of an aborted growth. */
    private void refund(Refund refund) {
        Player player = refund.player();
        if (player == null || (refund.item() == null && refund.experience() <= 0) || !player.isOnline()) {
            return;
        }
        if (refund.item() != null) {
            ItemStack single = refund.item().clone();
            if (single != null) {
                single.setAmount(refund.amount());
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(single);
                if (leftover != null) {
                    for (ItemStack rest : leftover.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), rest);
                    }
                }
            }
        }
        if (refund.experience() > 0) {
            plugin.core().player().depositExperience(player, refund.experience());
        }
        plugin.actionBar(player, "growth.refunded");
    }

    private static void cancelCatalyst(PlayerInteractEvent event) {
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
    }

    private void rejectNoRoom(PlayerInteractEvent event, Player player) {
        cancelCatalyst(event);
        plugin.actionBar(player, "growth.no-room");
        plugin.tutorialHint(player);
    }

    record GiantGrid(World world, int x, int y, int z, TreeSpecies species) {
    }

    record ColossalGrid(World world, int x, int y, int z, TreeSpecies species) {
    }

    record SaplingPlan(World world, int x, int y, int z, TreeSpecies species, int width) {
    }

    record ConnectedPlan(World world, int y, TreeSpecies species, List<StructurePos> members,
            Map<StructurePos, TreeSpecies> memberSpecies, boolean mixed, boolean allowMixed) {
    }

    private record PreviewPlan(Growth growth, String pattern, int clearanceHeight, int radius, SpaceState space) {
    }

    private enum SpaceState {
        CLEAR, BLOCKED, UNLOADED
    }

    private record TreeKey(UUID worldId, int x, int y, int z) {
    }

    /** Everything needed to grow one detected formation later on the main thread. */
    private record Growth(World world, TreeSpecies species, TreeKey key, List<StructurePos> saplings,
            TreeType treeType, double centerX, int y, double centerZ, long seedSalt, long timeFactor,
            boolean protectedByDefault, boolean profiled, CoalGrowthCosts.GrowthClass growthClass, Predicate<TreeSettings> stillValid,
            GrowthBuilder build, Runnable counter, int particles, float pitch) {
    }

    @FunctionalInterface
    private interface GrowthBuilder {
        void build(StructureAccess access, TreeGenerator builder, TreeSettings settings);
    }

    @FunctionalInterface
    interface GrowthEffects {
        void play(World world, double x, int y, double z, int particles, float pitch);
    }

    private record Refund(Player player, ItemStack item, int experience, int amount) {
        static final Refund NONE = new Refund(null, null, 0, 0);
    }

    private static final class PendingPlayerGrowth {
        private final Player player;
        private final CatalystUseLimiter.Reservation reservation;
        private final Refund refund;
        private boolean aborted;
        private boolean refunded;

        private PendingPlayerGrowth(Player player, CatalystUseLimiter.Reservation reservation, Refund refund) {
            this.player = player;
            this.reservation = reservation;
            this.refund = refund;
        }
    }

    private enum GrowthMethod {
        DEFAULT,
        BONEMEAL,
        GLOWSTONE,
        COAL;

        private static GrowthMethod from(Material material) {
            return switch (material) {
                case BONE_MEAL -> BONEMEAL;
                case GLOWSTONE_DUST -> GLOWSTONE;
                case COAL -> COAL;
                default -> null;
            };
        }
    }

    /** Access on the real world after the growth was applied; used for glowstone decoration. */
    private static final class TrackingWorldTreeAccess implements StructureAccess {
        private final World world;
        private final Set<StructurePos> changedPositions = new HashSet<>();
        private final List<StructurePos> glowstonePositions = new ArrayList<>();

        private TrackingWorldTreeAccess(World world) {
            this.world = world;
        }

        private void markChanged(StructurePos position) {
            changedPositions.add(position);
        }

        @Override
        public Material getType(int x, int y, int z) {
            return contains(x, y, z) ? world.getBlockAt(x, y, z).getType() : Material.BARRIER;
        }

        @Override
        public void setType(int x, int y, int z, Material material) {
            if (!contains(x, y, z)) {
                return;
            }
            world.getBlockAt(x, y, z).setType(material, false);
            changedPositions.add(new StructurePos(x, y, z));
            if (material == Material.GLOWSTONE) {
                glowstonePositions.add(new StructurePos(x, y, z));
            }
        }

        @Override
        public void setBlockData(int x, int y, int z, BlockData data) {
            if (!contains(x, y, z)) {
                return;
            }
            world.getBlockAt(x, y, z).setBlockData(data, false);
            changedPositions.add(new StructurePos(x, y, z));
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return y >= world.getMinHeight() && y < world.getMaxHeight() && world.isChunkLoaded(x >> 4, z >> 4);
        }

        @Override
        public int getLightLevel(int x, int y, int z) {
            return contains(x, y, z) ? world.getBlockAt(x, y, z).getLightLevel() : 0;
        }

        private Set<StructurePos> changedPositions() {
            return changedPositions;
        }

        private List<StructurePos> glowstonePositions() {
            return glowstonePositions;
        }
    }
}
