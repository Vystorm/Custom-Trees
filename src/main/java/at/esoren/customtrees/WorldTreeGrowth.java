package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.*;
import org.bukkit.scheduler.BukkitTask;

/**
 * Explicit bone-meal growth; one bounded job per server. Obstacles never abort a growth: every planned block is
 * either placed (air, plants, leaves, snow, soft natural ground; optionally natural stone; water for roots) or
 * skipped (buildings, containers, other trees' wood, fluids, bedrock...). Missing chunks of the footprint are loaded
 * asynchronously and held by a temporary plugin ticket only while the job runs.
 */
final class WorldTreeGrowth implements AutoCloseable {
    record Formation(World world, StructurePos origin, Material plant, int width) {
        Formation(World world,StructurePos origin,Material plant){this(world,origin,plant,10);}
    }
    /** Fitted dimensions: {@code shrunk} when the build limit forced a smaller tree. */
    record Size(int height, int radius, boolean shrunk) {}
    /** Upper bound of chunks one growth may load and hold (a 192/48 tree needs about 81). */
    static final int MAX_CHUNKS = 400;
    static final long CHUNK_LOAD_TIMEOUT_SECONDS = 60;
    private final CustomTreesPlugin plugin;
    private final CoreProtectBridge coreProtect;
    private Job job;
    private long grownTotal, partialTotal;
    /** Completed and partly placed world trees since start, for /customtrees status. */
    long grownTotal() { return grownTotal; }
    long partialTotal() { return partialTotal; }
    boolean growing() { return job != null; }
    private boolean firing;

    WorldTreeGrowth(CustomTreesPlugin plugin) { this.plugin = plugin; coreProtect = new CoreProtectBridge(plugin); }
    boolean ownsEvent() { return firing; }

    /** Reserves only complete 10x10 formations; smaller fungi keep vanilla dispenser, off-hand and no-permission growth. */
    boolean interceptNatural(Block block) {
        return enabled(block.getWorld()) && find(block, 10) != null;
    }
    private boolean enabled(World world) {
        return plugin.worldTreeSettings().enabled()
                && (world.getEnvironment() == World.Environment.NORMAL || world.getEnvironment() == World.Environment.NETHER)
                && (plugin.treeSettings().enabledWorlds().isEmpty()
                    || plugin.treeSettings().enabledWorlds().contains(world.getName().toLowerCase(Locale.ROOT)));
    }

    boolean interact(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || !enabled(block.getWorld()) || !event.getPlayer().hasPermission("customtrees.grow")) return false;
        Material item = event.getItem() == null ? Material.AIR : event.getItem().getType();
        boolean preview = event.getPlayer().isSneaking() && item == Material.AIR;
        if (!preview && item != Material.BONE_MEAL && item != Material.COAL && item != Material.GLOWSTONE_DUST) return false;
        Formation formation = find(block);
        if (formation == null) return false;
        // Smaller fungus squares only take over a valid bone-meal growth; otherwise vanilla behaviour is unchanged.
        if (formation.width() < 10 && !preview && (item != Material.BONE_MEAL || !validSoil(formation))) return false;
        event.setCancelled(true); event.setUseItemInHand(Event.Result.DENY); event.setUseInteractedBlock(Event.Result.DENY);
        Player player = event.getPlayer();
        var settings = plugin.worldTreeSettings();
        if (preview) {
            plugin.message(player,"world-tree.preview", "size",formation.width(),
                    "height",settings.height(formation.plant(),formation.world().getEnvironment(),formation.width()),
                    "radius",settings.radius(formation.plant(),formation.width()));
            return true;
        }
        if (item != Material.BONE_MEAL) { plugin.message(player,"world-tree.bonemeal"); return true; }
        if (job != null) { plugin.message(player,"world-tree.busy"); return true; }
        if (!validSoil(formation)) { plugin.message(player,"world-tree.no-room"); return true; }
        for (StructurePos pos : members(formation)) {
            if (plugin.plantings() != null && plugin.plantings().at(formation.world().getBlockAt(pos.x(),pos.y(),pos.z())) != null) {
                plugin.message(player,"garden.pattern-mismatch"); return true;
            }
        }
        Size size = fit(formation, settings.height(formation.plant(), formation.world().getEnvironment(),formation.width()),
                settings.radius(formation.plant(),formation.width()));
        if (size == null) { plugin.message(player,"world-tree.too-high"); return true; }
        if (size.shrunk()) plugin.message(player,"world-tree.shrunk","height",size.height(),"radius",size.radius());
        List<long[]> chunks = footprintChunks(formation, size.height(), size.radius());
        if (chunks.size() > MAX_CHUNKS) { plugin.message(player,"world-tree.too-large"); return true; }
        Job started = new Job(formation, player, settings, size);
        job = started;
        plugin.message(player,"world-tree.planning");
        loadChunks(started, chunks);
        return true;
    }

    /** Loads every footprint chunk asynchronously, then holds them with a temporary plugin ticket and plans the tree. */
    void loadChunks(Job started, List<long[]> chunks) {
        World world = started.formation.world();
        CompletableFuture<?>[] futures = new CompletableFuture<?>[chunks.size()];
        for (int i = 0; i < futures.length; i++) {
            long[] c = chunks.get(i);
            CompletableFuture<Chunk> future;
            try { future = plugin.core().compat().platform().getChunkAtAsync(world, (int) c[0], (int) c[1], true); }
            catch (RuntimeException failure) { future = CompletableFuture.failedFuture(failure); }
            futures[i] = future == null ? CompletableFuture.failedFuture(new IllegalStateException("no chunk future")) : future;
        }
        CompletableFuture.allOf(futures).orTimeout(CHUNK_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((ignored, failure) -> {
            Runnable resume = () -> {
                if (job != started) return;
                if (failure != null) {
                    plugin.getLogger().warning("World-tree chunk loading failed: " + failure);
                    if (started.player.isOnline()) plugin.message(started.player,"world-tree.load-failed");
                    finish(started,false,false); return;
                }
                for (long[] c : chunks) {
                    world.addPluginChunkTicket((int) c[0], (int) c[1], plugin);
                    started.tickets.add(c);
                }
                plan(started);
            };
            if (Bukkit.isPrimaryThread()) resume.run();
            else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, resume);
        });
    }

    private void plan(Job started) {
        Formation formation = started.formation;
        int height = started.size.height(), radius = started.size.radius();
        long seed = formation.world().getSeed() ^ formation.origin().hashCode() ^ formation.world().getGameTime();
        var generator = plugin.core().structureGenerator();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<StructurePos,Material> plan = new LinkedHashMap<>(plugin.core().structureGenerator().supportsFungus(formation.plant())
                        ? generator.fungus(formation.plant(),formation.origin(),seed,formation.width(),height,radius)
                        : generator.worldTree(formation.plant(),formation.origin(),seed,height,radius));
                members(formation).forEach(p -> plan.putIfAbsent(p,Material.AIR));
                if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> {
                    if (job != started) return;
                    started.plan = plan;
                    started.iterator = plan.entrySet().iterator();
                    started.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> step(started),1,1);
                });
            } catch (RuntimeException failure) {
                plugin.getLogger().warning("World-tree planning failed: " + failure.getMessage());
                if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> { if(job == started) finish(started,false); });
            }
        });
    }

    static Formation find(Block member) {
        for(int width:FungusPlan.supports(member.getType())?new int[]{10,5,3,2,1}:new int[]{10}) {
            Formation found=find(member,width);
            if(found!=null)return found;
        }
        return null;
    }
    static Formation find(Block member,int width) {
        Material plant = member.getType();
        if (!WorldTreePlan.supports(plant)) return null;
        World world = member.getWorld(); int y = member.getY();
        for (int dx = 0; dx < width; dx++) for (int dz = 0; dz < width; dz++) {
            int x = member.getX()-dx, z = member.getZ()-dz;
            boolean matches = true;
            for (int a = 0; a < width && matches; a++) for (int b = 0; b < width; b++) {
                if (!world.isChunkLoaded((x+a)>>4,(z+b)>>4) || world.getBlockAt(x+a,y,z+b).getType() != plant) {
                    matches = false; break;
                }
            }
            if (matches) return new Formation(world,new StructurePos(x,y,z),plant,width);
        }
        return null;
    }
    static Set<StructurePos> members(Formation f) {
        Set<StructurePos> result = new LinkedHashSet<>();
        for(int x=0;x<f.width();x++)for(int z=0;z<f.width();z++)result.add(f.origin().add(x,0,z));
        return result;
    }
    private static boolean validSoil(Formation f) {
        for (StructurePos pos : members(f)) {
            if (pos.y() <= f.world().getMinHeight()) return false;
            Material soil = f.world().getBlockAt(pos.x(),pos.y()-1,pos.z()).getType();
            boolean valid = switch(f.plant()) {
                case CRIMSON_FUNGUS -> soil == Material.CRIMSON_NYLIUM;
                case WARPED_FUNGUS -> soil == Material.WARPED_NYLIUM;
                default -> GeneratedTreeScanner.SOILS.contains(soil) || soil == Material.MYCELIUM;
            };
            if (!valid) return false;
        }
        return true;
    }

    /** Exclusive top Y the crown may use: the build limit, or the Nether roof when planted below it. */
    static int topLimit(World world, int originY) {
        int top = world.getMaxHeight(), logical = world.getLogicalHeight();
        if (world.getEnvironment() == World.Environment.NETHER && logical > 0 && originY < world.getMinHeight() + logical)
            top = Math.min(top, world.getMinHeight() + logical);
        return top;
    }
    /** Underground root depth of the plan for this size (0 for fungi). */
    static int rootDepth(Formation f, int height, int radius) {
        if (f.width() != 10) return 0;
        return WorldTreePlan.depth(f.plant(), height, radius);
    }
    /**
     * Shrinks height (and radius proportionally, never below Core's minimums) until the crown fits below the build
     * limit and, where possible, the roots fit above the world's minimum height. Null when even the minimum height
     * does not fit; roots that still reach below the world bottom are simply left out.
     */
    static Size fit(Formation f, int height, int radius) {
        boolean full = f.width() == 10;
        int minHeight = full ? 32 : 6, minRadius = full ? 12 : 2;
        int y = f.origin().y(), available = topLimit(f.world(), y) - y;
        int h = Math.min(height, available);
        if (h < minHeight) return null;
        int below = y - f.world().getMinHeight();
        while (h > minHeight && rootDepth(f, h, radius) > below) h--;
        if (h == height) return new Size(height, radius, false);
        int r = Math.max(minRadius, Math.min(radius, (int) Math.round(radius * (double) h / height)));
        return new Size(h, r, true);
    }
    /** Chunk coordinates covering the plan's horizontal footprint. */
    static List<long[]> footprintChunks(Formation f, int height, int radius) {
        int extent = f.width() == 10 ? WorldTreePlan.footprint(f.plant(), height, radius) : Math.max(14, radius) + f.width() + 1;
        int cx = f.origin().x() + f.width() / 2, cz = f.origin().z() + f.width() / 2;
        List<long[]> result = new ArrayList<>();
        for (int x = (cx - extent) >> 4; x <= (cx + extent) >> 4; x++)
            for (int z = (cz - extent) >> 4; z <= (cz + extent) >> 4; z++) result.add(new long[]{x, z});
        return result;
    }

    enum Fit { PLACE, SKIP }
    /** Plants, flowers, grasses, ferns, vines, moss, snow and other natural cover that a growing tree pushes aside. */
    private static final Set<Material> COVER = PaperCompat.materials(Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN,
            Material.LARGE_FERN, Material.VINE, Material.SNOW, Material.SNOW_BLOCK, Material.POWDER_SNOW, Material.DEAD_BUSH,
            Material.GLOW_LICHEN, Material.HANGING_ROOTS, PaperCompat.material("PALE_HANGING_MOSS"), Material.MOSS_CARPET,
            PaperCompat.material("PALE_MOSS_CARPET"), Material.MOSS_BLOCK, PaperCompat.material("PALE_MOSS_BLOCK"), PaperCompat.material("SHORT_DRY_GRASS"),
            PaperCompat.material("TALL_DRY_GRASS"), PaperCompat.material("BUSH"), PaperCompat.material("LEAF_LITTER"), PaperCompat.material("FIREFLY_BUSH"), Material.DANDELION,
            Material.POPPY, Material.BLUE_ORCHID, Material.ALLIUM, Material.AZURE_BLUET, Material.RED_TULIP,
            Material.ORANGE_TULIP, Material.WHITE_TULIP, Material.PINK_TULIP, Material.OXEYE_DAISY, Material.CORNFLOWER,
            Material.LILY_OF_THE_VALLEY, Material.SUNFLOWER, Material.LILAC, Material.ROSE_BUSH, Material.PEONY,
            Material.PINK_PETALS, PaperCompat.material("WILDFLOWERS"), Material.TORCHFLOWER, Material.PITCHER_PLANT,
            PaperCompat.material("OPEN_EYEBLOSSOM"), PaperCompat.material("CLOSED_EYEBLOSSOM"), PaperCompat.material("CACTUS_FLOWER"), Material.WITHER_ROSE,
            Material.SPORE_BLOSSOM, Material.AZALEA, Material.FLOWERING_AZALEA, Material.SMALL_DRIPLEAF,
            Material.BIG_DRIPLEAF, Material.BIG_DRIPLEAF_STEM, Material.CAVE_VINES, Material.CAVE_VINES_PLANT,
            Material.LILY_PAD, Material.SWEET_BERRY_BUSH, Material.BROWN_MUSHROOM, Material.RED_MUSHROOM,
            Material.MANGROVE_PROPAGULE, Material.CRIMSON_ROOTS, Material.WARPED_ROOTS, Material.NETHER_SPROUTS,
            Material.CRIMSON_FUNGUS, Material.WARPED_FUNGUS, Material.WEEPING_VINES, Material.WEEPING_VINES_PLANT,
            Material.TWISTING_VINES, Material.TWISTING_VINES_PLANT,
            // Soft natural ground (never farmland, ice or stone).
            Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.PODZOL,
            Material.MYCELIUM, Material.DIRT_PATH, Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.CLAY,
            Material.MUD, Material.MUDDY_MANGROVE_ROOTS);
    /** Natural stone replaced only with {@code world-trees.replace-stone: true}; ores are never part of it. */
    private static final Set<Material> STONE = EnumSet.of(Material.STONE, Material.DEEPSLATE, Material.ANDESITE,
            Material.DIORITE, Material.GRANITE, Material.TUFF, Material.CALCITE, Material.SANDSTONE, Material.RED_SANDSTONE,
            Material.TERRACOTTA, Material.WHITE_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.YELLOW_TERRACOTTA,
            Material.BROWN_TERRACOTTA, Material.RED_TERRACOTTA, Material.LIGHT_GRAY_TERRACOTTA);
    /** Water and underwater plants: roots grow through them, the crown never does. */
    private static final Set<Material> WATER = EnumSet.of(Material.WATER, Material.BUBBLE_COLUMN, Material.SEAGRASS,
            Material.TALL_SEAGRASS, Material.KELP, Material.KELP_PLANT, Material.SEA_PICKLE);

    /**
     * Never {@code BLOCKED}: a block is either replaced or left out. {@code root} marks wood at or below the planting
     * level (roots and trunk base), which may also displace water and waterlogged plants.
     */
    static Fit fit(Material old, boolean waterlogged, boolean member, Material plant, boolean root, boolean replaceStone) {
        if (member && old == plant || old == Material.AIR || old == Material.CAVE_AIR || old == Material.VOID_AIR) return Fit.PLACE;
        if (root && WATER.contains(old)) return Fit.PLACE;
        if (WATER.contains(old) || waterlogged && !root) return Fit.SKIP; // keep fluids untouched
        if (COVER.contains(old) || old.name().endsWith("_LEAVES") || old.name().endsWith("_SAPLING")) return Fit.PLACE;
        if (replaceStone && STONE.contains(old)) return Fit.PLACE;
        return Fit.SKIP;
    }
    static boolean woodLike(Material material) {
        String name = material.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD") || material == Material.MANGROVE_ROOTS || material == Material.MUSHROOM_STEM
                || material == Material.CRIMSON_STEM || material == Material.WARPED_STEM;
    }
    /** Roots and trunk base: planned wood at or just above (one block) the planting level. */
    static boolean rootBlock(StructurePos pos, Material planned, int originY) {
        return pos.y() <= originY + 1 && woodLike(planned);
    }

    /** Vine faces from the plan: attach to a horizontal solid neighbour, else inherit from the vine above (hanging curtain). */
    static Set<BlockFace> vineFaces(Map<StructurePos,Material> plan, StructurePos pos) {
        Set<BlockFace> faces = EnumSet.noneOf(BlockFace.class);
        for (StructurePos at = pos; plan.get(at) == Material.VINE; at = at.add(0,1,0)) {
            for (BlockFace face : new BlockFace[]{BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST}) {
                Material side = plan.get(at.add(face.getModX(),0,face.getModZ()));
                if (side != null && side != Material.VINE && side != Material.AIR && side != PaperCompat.PALE_HANGING_MOSS) faces.add(face);
            }
            if (!faces.isEmpty()) return faces;
            Material above = plan.get(at.add(0,1,0));
            if (above != null && above != Material.VINE && above != Material.AIR) { if (at.equals(pos)) faces.add(BlockFace.UP); return faces; }
        }
        return faces;
    }
    private static BlockData data(Job j, StructurePos pos, Material material) {
        if (material == Material.VINE) {
            Set<BlockFace> faces = vineFaces(j.plan, pos);
            if (faces.isEmpty()) return null;
            MultipleFacing vine = (MultipleFacing) material.createBlockData();
            faces.forEach(face -> vine.setFace(face, true));
            return vine;
        }
        if (material == PaperCompat.PALE_HANGING_MOSS) {
            return PaperCompat.blocks().hangingMoss(material, j.plan.get(pos.add(0,-1,0)) != PaperCompat.PALE_HANGING_MOSS);
        }
        return j.data.computeIfAbsent(material, m -> {
            BlockData data = m.createBlockData();
            if (data instanceof Leaves leaves) { leaves.setPersistent(true); leaves.setDistance(1); }
            return data;
        });
    }

    private static boolean loaded(World world, StructurePos p) {
        return p.y() >= world.getMinHeight() && p.y() < world.getMaxHeight() && world.isChunkLoaded(p.x()>>4,p.z()>>4);
    }

    private void step(Job current) {
        try {
            if (!current.committing && (!current.player.isOnline() || !current.player.getWorld().equals(current.formation.world())
                    || !current.player.hasPermission("customtrees.grow") || !enabled(current.formation.world()))) {
                finish(current,false); return;
            }
            if (!current.committing) validate(current);
            else commit(current);
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("World-tree growth stopped: " + failure.getMessage());
            finish(current,false);
        }
    }

    private void validate(Job j) {
        World world = j.formation.world();
        int originY = j.formation.origin().y();
        for (int i=0;i<j.settings.blocksPerTick() && j.iterator.hasNext();i++) {
            var entry = j.iterator.next(); StructurePos pos = entry.getKey();
            boolean member = j.members.contains(pos);
            // Outside the world, the border or a (rarely) unloaded chunk: leave the block out, never abort.
            if (!loaded(world,pos) || !world.getWorldBorder().isInside(new Location(world,pos.x()+.5,pos.y(),pos.z()+.5))) {
                j.skippedPlan++; continue;
            }
            Block block = world.getBlockAt(pos.x(),pos.y(),pos.z());
            BlockData current = block.getBlockData();
            boolean waterlogged = current instanceof Waterlogged w && w.isWaterlogged();
            Fit fit = fit(block.getType(), waterlogged, member, j.formation.plant(),
                    rootBlock(pos, entry.getValue(), originY), j.settings.replaceStone());
            if (fit == Fit.SKIP) { j.skippedPlan++; continue; }
            BlockData data = data(j,pos,entry.getValue());
            // The plants themselves are always consumed, even where the plan leaves an unsupported vine.
            if (data == null && member) data = data(j,pos,Material.AIR);
            if (data == null) { j.skippedPlan++; continue; }
            j.originals.put(pos,current.clone());
            BlockState state = block.getState();
            state.setBlockData(data); j.states.add(state);
        }
        if(j.iterator.hasNext()) return;
        // Optional safety net (default 100 = never refuse): an almost completely buried plan.
        if (j.skippedPlan * 100L > (long) j.plan.size() * j.settings.maxSkippedPercent()) {finish(j,false);return;}
        if (!matches(j.formation) || !validSoil(j.formation)) {finish(j,false);return;}
        StructurePos origin = j.formation.origin();
        var event = new StructureGrowEvent(new Location(world,origin.x(),origin.y(),origin.z()),treeType(j.formation.plant()),true,j.player,j.states);
        firing=true;
        try {Bukkit.getPluginManager().callEvent(event);} finally {firing=false;}
        if(event.isCancelled() || event.getBlocks().isEmpty()) {finish(j,false);return;}
        // Only accept unique positions belonging to the validated plan in this exact world.
        Set<StructurePos> seen = new HashSet<>();
        j.accepted = new ArrayList<>();
        for(BlockState state:event.getBlocks()) {
            StructurePos p=new StructurePos(state.getX(),state.getY(),state.getZ());
            if(world.equals(state.getWorld()) && j.originals.containsKey(p) && seen.add(p)) j.accepted.add(state);
        }
        // A filtered formation must not silently consume all one hundred plants.
        if(!seen.containsAll(j.members)) {finish(j,false);return;}
        ItemStack hand=j.player.getInventory().getItemInMainHand();
        if(hand.getType()!=Material.BONE_MEAL || hand.getAmount()<1) {finish(j,false);return;}
        if(j.player.getGameMode()!=GameMode.CREATIVE) {
            j.refund=hand.clone();j.refund.setAmount(1);
            ItemStack before=hand.clone();hand.setAmount(hand.getAmount()-1);
            plugin.core().inventoryEvents().observeUse(j.player,j.player.getInventory().getHeldItemSlot(),before,()->true);
        }
        j.committing=true;
        plugin.message(j.player,"world-tree.growing","blocks",j.accepted.size());
    }

    private void commit(Job j) {
        List<Block> marked=new ArrayList<>();List<StructurePos> logs=new ArrayList<>();
        for(int i=0;i<j.settings.blocksPerTick() && j.index<j.accepted.size();i++) {
            BlockState state=j.accepted.get(j.index++);World world=j.formation.world();
            StructurePos p=new StructurePos(state.getX(),state.getY(),state.getZ());
            if(!loaded(world,p)) {j.skipped++;continue;}
            Block block=world.getBlockAt(p.x(),p.y(),p.z());
            // Do not overwrite a player's construction made during the staged growth.
            if(!block.getBlockData().equals(j.originals.get(p))) {j.skipped++;continue;}
            if(state.update(true,false)) {
                j.written++; marked.add(block);
                Material material=state.getType();
                if(woodLike(material)) logs.add(p);
            } else j.skipped++;
        }
        if(plugin.fireGuard()!=null) plugin.fireGuard().markAll(marked);
        if(!logs.isEmpty()) Bukkit.getScheduler().runTaskLater(plugin,()->coreProtect.logPlacements(j.formation.world(),logs),GiantSaplingListener.COREPROTECT_LOG_DELAY_TICKS);
        if(j.index>=j.accepted.size()) finish(j,true);
    }
    static boolean matches(Formation f) {
        for(StructurePos p:members(f))if(!loaded(f.world(),p) || f.world().getBlockAt(p.x(),p.y(),p.z()).getType()!=f.plant())return false;
        return true;
    }
    private static TreeType treeType(Material plant) {
        return switch(plant.name()) {
            case "RED_MUSHROOM" -> TreeType.RED_MUSHROOM;
            case "BROWN_MUSHROOM" -> TreeType.BROWN_MUSHROOM;
            case "CRIMSON_FUNGUS" -> TreeType.CRIMSON_FUNGUS;
            case "WARPED_FUNGUS" -> TreeType.WARPED_FUNGUS;
            case "SPRUCE_SAPLING" -> TreeType.MEGA_REDWOOD;
            case "BIRCH_SAPLING" -> TreeType.TALL_BIRCH;
            case "JUNGLE_SAPLING" -> TreeType.JUNGLE;
            case "ACACIA_SAPLING" -> TreeType.ACACIA;
            case "DARK_OAK_SAPLING" -> TreeType.DARK_OAK;
            case "MANGROVE_PROPAGULE" -> TreeType.TALL_MANGROVE;
            case "CHERRY_SAPLING" -> TreeType.CHERRY;
            case "PALE_OAK_SAPLING" -> PaperCompat.treeType("PALE_OAK", TreeType.DARK_OAK);
            default -> TreeType.BIG_TREE;
        };
    }
    private void finish(Job j, boolean complete) { finish(j, complete, true); }
    private void finish(Job j, boolean complete, boolean report) {
        if(j.task!=null)j.task.cancel();
        // Temporary tickets only live as long as the job; the chunks unload normally afterwards.
        for(long[] c:j.tickets) {
            try { j.formation.world().removePluginChunkTicket((int) c[0],(int) c[1],plugin); }
            catch (RuntimeException ignored) { /* world unloaded */ }
        }
        j.tickets.clear();
        if(j.written==0 && j.refund!=null && j.player.isOnline())
            j.player.getInventory().addItem(j.refund).values().forEach(item->j.player.getWorld().dropItemNaturally(j.player.getLocation(),item));
        if(complete && j.skipped==0)grownTotal++; else if(j.written>0)partialTotal++;
        if(report && j.player.isOnline())plugin.message(j.player,complete && j.skipped==0?"world-tree.complete":j.written>0?"world-tree.partial":"world-tree.no-room");
        // Herbarium: a finished growth counts even if obstacles were left out.
        if(complete && j.written>0 && j.player.isOnline() && plugin.herbarium()!=null) {
            try { plugin.herbarium().worldGrown(j.player,j.formation.plant(),j.formation.width()); }
            catch (RuntimeException failure) { Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages.mid("[CustomTrees] Herbarium update failed: " + failure); }
        }
        if(job==j)job=null;
    }
    @Override public void close(){if(job!=null)finish(job,false);}

    static final class Job {
        final Formation formation;final Player player;final WorldTreeSettings settings;final Set<StructurePos> members;final Size size;
        final List<long[]> tickets=new ArrayList<>();
        Map<StructurePos,Material> plan;Iterator<Map.Entry<StructurePos,Material>> iterator;
        final Map<StructurePos,BlockData> originals=new HashMap<>();final List<BlockState> states=new ArrayList<>();
        final Map<Material,BlockData> data=new EnumMap<>(Material.class);
        List<BlockState> accepted;BukkitTask task;ItemStack refund;boolean committing;int index,written,skipped,skippedPlan;
        Job(Formation f,Player p,WorldTreeSettings s,Size size){formation=f;player=p;settings=s;this.size=size;members=members(f);}
        Job(Formation f,Player p,WorldTreeSettings s){this(f,p,s,new Size(s.height(f.plant(),World.Environment.NORMAL,f.width()),s.radius(f.plant(),f.width()),false));}
    }
}
