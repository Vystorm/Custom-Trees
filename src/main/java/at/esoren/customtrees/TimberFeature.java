package at.esoren.customtrees;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core._uiMethods.settings.Setting;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.region.Region;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Built-in timber: chopping a log of a natural or CustomTrees tree fells the whole tree, either straight down
 * ({@code drop}) or as a real tree fall ({@code fall}) shown with block displays that turn into items on impact.
 * <p>
 * Flow per chop: chunk snapshots are captured on the server thread (budgeted), the tree is detected off-thread
 * ({@link TimberScanner}, optional CoreProtect lookup), then every block is offered to protection plugins as a
 * {@link TimberBreakEvent} and removed in budgeted batches. Display entities are non-persistent, tagged and removed on
 * finish, chunk unload, plugin disable and on load.
 */
final class TimberFeature implements Listener {
    static final List<String> OTHER_TIMBER_PLUGINS = List.of("SmoothTimber", "UltimateTimber", "TreeAssist",
            "TreeCapitator", "TreeFeller", "Timber", "TimberX", "Lumberjack", "LumberJack", "TreeTimber", "FastTreeFeller");
    static final String PERMISSION = "customtrees.timber";
    static final String PREF_USE = "timber-use", PREF_MODE = "timber-mode", SECTION_ID = "timber";
    private static final int SMALL_RADIUS = 12, CROWN_MARGIN = 8, SNAPSHOTS_PER_TICK = 16,
            PER_BLOCK_CHECK_LIMIT = 4096, LEAF_DROP_SAMPLE = 2000, DROP_CELL = 3, MERGE_AT = 256, OBSTACLE_SAMPLE = 300;

    private final CustomTreesPlugin plugin;
    private final Lang lang;
    private volatile TimberOptions options = TimberOptions.DEFAULTS;
    private final ChopProgress progress = new ChopProgress();
    private final TimberGuards.Limits limits = new TimberGuards.Limits();
    private final TimberCoreProtect coreProtect = new TimberCoreProtect();
    private final List<Scan> scans = new ArrayList<>();
    private final List<Fell> fells = new ArrayList<>();
    private volatile String conflict;
    private String reportedConflict = "";
    private BukkitTask ticker;
    private boolean settingsRegistered;
    private long felled, blocksRemoved, displaysSpawned, refused, replanted;

    TimberFeature(CustomTreesPlugin plugin, Lang lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    TimberOptions options() { return options; }

    void apply(TimberOptions next) {
        options = next;
        if (ticker != null) checkConflicts();
    }

    /** Registers listener, ticker and the personal settings; removes displays a crash may have left behind. */
    void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        int removed = 0;
        for (World world : Bukkit.getWorlds()) removed += TimberGuards.cleanup(world.getEntitiesByClass(BlockDisplay.class));
        if (removed > 0) DebugMessages.mid("[CustomTrees/Timber] Removed " + removed + " leftover timber displays");
        // Other plugins enable after CustomTrees (load: STARTUP): look for a second timber once everything is up.
        Bukkit.getScheduler().runTask(plugin, this::checkConflicts);
        registerSettings();
    }

    /** Finishes every running fell at once (drops land, displays go), then stops. */
    void stop() {
        for (Fell fell : new ArrayList<>(fells)) finishNow(fell);
        fells.clear();
        for (Scan scan : scans) limits.release(scan.world.getUID(), scan.playerId);
        scans.clear();
        progress.clear();
        limits.clear();
        if (ticker != null) { ticker.cancel(); ticker = null; }
        for (World world : Bukkit.getWorlds()) TimberGuards.cleanup(world.getEntitiesByClass(BlockDisplay.class));
    }

    // ---- personal settings (/vsettings) ----

    private void registerSettings() {
        if (settingsRegistered) return;
        try {
            LinkedHashMap<String, String> modes = new LinkedHashMap<>();
            modes.put("server", lang.ref("timber.player.mode-server"));
            modes.put("drop", lang.ref("timber.player.mode-drop"));
            modes.put("fall", lang.ref("timber.player.mode-fall"));
            SettingsSection section = SettingsSection.player(SECTION_ID, lang.ref("timber.player.title"),
                    lang.ref("timber.player.description"), List.of(
                            Setting.toggle(PREF_USE, lang.ref("timber.player.use.label"), lang.ref("timber.player.use.description"),
                                    player -> plugin.core().settings().prefs().bool(plugin, player, PREF_USE, true),
                                    (player, value) -> {
                                        if (!options.playerChoice()) return lang.ref("timber.player.locked");
                                        plugin.core().settings().prefs().setBool(plugin, player, PREF_USE, value);
                                        return null;
                                    }).permission(PERMISSION),
                            Setting.choice(PREF_MODE, lang.ref("timber.player.mode.label"), lang.ref("timber.player.mode.description"), modes,
                                    player -> TimberOptions.PlayerMode.parse(plugin.core().settings().prefs().text(plugin, player, PREF_MODE, "server")).id(),
                                    (player, value) -> {
                                        if (!options.playerChoice()) return lang.ref("timber.player.locked");
                                        plugin.core().settings().prefs().setText(plugin, player, PREF_MODE, TimberOptions.PlayerMode.parse(value).id());
                                        return null;
                                    }).permission(PERMISSION)));
            plugin.core().settings().register(plugin, section);
            settingsRegistered = true;
        } catch (RuntimeException failure) {
            DebugMessages.mid("[CustomTrees/Timber] Personal timber settings unavailable: " + failure);
        }
    }

    private boolean personallyEnabled(Player player) {
        if (!options.playerChoice()) return true;
        try { return plugin.core().settings().prefs().bool(plugin, player, PREF_USE, true); }
        catch (RuntimeException failure) { return true; }
    }

    private TimberOptions.PlayerMode personalMode(Player player) {
        if (!options.playerChoice()) return TimberOptions.PlayerMode.SERVER;
        try { return TimberOptions.PlayerMode.parse(plugin.core().settings().prefs().text(plugin, player, PREF_MODE, "server")); }
        catch (RuntimeException failure) { return TimberOptions.PlayerMode.SERVER; }
    }

    // ---- other timber plugins ----

    /** Name of an enabled timber plugin, or null. */
    static String otherTimber() {
        for (String name : OTHER_TIMBER_PLUGINS) {
            var other = Bukkit.getPluginManager().getPlugin(name);
            if (other != null && other.isEnabled()) return other.getName();
        }
        return null;
    }

    private void checkConflicts() {
        String found = otherTimber();
        conflict = found;
        String key = found == null ? "" : found + "/" + options.pauseForOtherTimber() + "/" + options.enabled();
        if (key.equals(reportedConflict)) return;
        reportedConflict = key;
        if (found == null || !options.enabled()) return;
        if (options.pauseForOtherTimber()) {
            plugin.getLogger().warning(found + " is installed, so the built-in CustomTrees timber is paused"
                    + " (timber.other-timber-plugins: pause). Remove " + found + " or set run-both to use the built-in timber.");
        } else {
            plugin.getLogger().warning(found + " and the built-in CustomTrees timber both run (timber.other-timber-plugins:"
                    + " run-both); both may react to the same chop. Pick one of them.");
        }
    }

    boolean paused() { return conflict != null && options.pauseForOtherTimber(); }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) { if (ticker != null && event.getPlugin() != plugin) checkConflicts(); }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) { if (ticker != null && event.getPlugin() != plugin) checkConflicts(); }

    /** Status line of {@code /customtrees status}. */
    String status() {
        TimberOptions o = options;
        return String.format(Locale.ROOT, "CustomTrees timber: enabled=%s, mode=%s, trigger=%s%s, paused-by=%s, felled=%d, blocks=%d, displays=%d, refused=%d, replanted=%d, running=%d, chopping=%d",
                o.enabled(), o.mode().id(), o.trigger().id(), o.trigger() == TimberOptions.Trigger.PERCENT ? " " + o.trunkPercent() + "%" : "",
                paused() ? conflict : "none", felled, blocksRemoved, displaysSpawned, refused, replanted, fells.size() + scans.size(), progress.size());
    }

    // ---- chop ----

    /**
     * Block break and place go through Core's stages, so Core decides when they run among all Vystorm plugins.
     * The timber starts from a break that really happened and never touches the event (every further block gets its
     * own TimberBreakEvent): OBSERVE, the final outcome, never a cancelled event.
     */
    void handleBlockEvents(org.bukkit.plugin.Plugin owner, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(owner, BlockBreakEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::onBreak);
    }

    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void onBreak(BlockBreakEvent event) {
        if (event instanceof TimberBreakEvent) return;
        TimberOptions o = options;
        if (!o.enabled() || paused()) return;
        Block block = event.getBlock();
        if (TimberBlocks.family(block.getType()) == null) return;
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR || !player.hasPermission(PERMISSION)) return;
        World world = block.getWorld();
        if (!o.worldAllowed(world.getName()) || !o.sneaking().allows(player.isSneaking())) return;
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (o.requireAxe() && !TimberBlocks.axe(tool.getType())) return;
        if (!personallyEnabled(player)) return;
        long position = TimberScanner.pack(block.getX(), block.getY(), block.getZ());
        long now = System.currentTimeMillis();
        if (o.trigger() == TimberOptions.Trigger.PERCENT) {
            ChopProgress.Cut cut = progress.find(world.getUID(), position, now);
            if (cut != null) {
                progress.chop(cut, position, now, o.percentMemorySeconds() * 1000L);
                if (!cut.ready()) {
                    plugin.actionBar(player, "timber.progress", "chopped", cut.chopped(), "required", cut.required);
                    return;
                }
                if (!limits.tryAcquire(world.getUID(), player.getUniqueId(), o.fallsPerWorld(), o.fallsPerPlayer())) {
                    plugin.actionBar(player, "timber.busy");
                    return;
                }
                plugin.actionBar(player, "timber.progress", "chopped", cut.chopped(), "required", cut.required);
                fell(player, tool.clone(), world, cut.plan);
                return;
            }
        }
        if (!limits.tryAcquire(world.getUID(), player.getUniqueId(), o.fallsPerWorld(), o.fallsPerPlayer())) {
            plugin.actionBar(player, "timber.busy");
            return;
        }
        scans.add(new Scan(player, world, block, tool.clone(), o.trigger(), Math.min(SMALL_RADIUS, o.maxRadius())));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        for (Iterator<Scan> it = scans.iterator(); it.hasNext(); ) {
            Scan scan = it.next();
            if (scan.playerId.equals(id) && !scan.async) { it.remove(); limits.release(scan.world.getUID(), id); }
        }
    }

    /** Chunk snapshots and CustomTrees markers around one chop, captured over a few ticks, then scanned off-thread. */
    private final class Scan {
        final Player player;
        final UUID playerId;
        final World world;
        final int ox, oy, oz;
        final TimberScanner.Cell origin;
        final ItemStack tool;
        final TimberOptions.Trigger trigger;
        int radius;
        final Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
        final Map<Long, Set<Integer>> marks = new HashMap<>();
        final List<long[]> pending = new ArrayList<>();
        boolean async;

        Scan(Player player, World world, Block block, ItemStack tool, TimberOptions.Trigger trigger, int radius) {
            this.player = player;
            this.playerId = player.getUniqueId();
            this.world = world;
            this.ox = block.getX(); this.oy = block.getY(); this.oz = block.getZ();
            this.origin = cell(block.getBlockData(), false);
            this.tool = tool;
            this.trigger = trigger;
            plan(radius);
        }

        void plan(int newRadius) {
            radius = newRadius;
            int reach = radius + CROWN_MARGIN;
            for (int cx = (ox - reach) >> 4; cx <= (ox + reach) >> 4; cx++)
                for (int cz = (oz - reach) >> 4; cz <= (oz + reach) >> 4; cz++)
                    if (!snapshots.containsKey(chunkKey(cx, cz))) pending.add(new long[] {cx, cz});
        }
    }

    static long chunkKey(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    static TimberScanner.Cell cell(BlockData data, boolean marked) {
        Material type = data.getMaterial();
        org.bukkit.Axis axis = data instanceof Orientable orientable ? orientable.getAxis() : null;
        if (data instanceof Leaves leaves) return new TimberScanner.Cell(type, axis, leaves.isPersistent(), leaves.getDistance(), marked);
        return new TimberScanner.Cell(type, axis, false, 7, marked);
    }

    /** Snapshot-backed view; the chopped log itself is overlaid (it may be gone from the world already). */
    private static final class SnapshotView implements TimberScanner.View {
        private final Map<Long, ChunkSnapshot> chunks;
        private final Map<Long, Set<Integer>> marks;
        private final long origin;
        private final TimberScanner.Cell originCell;
        private final int minY, maxY;

        SnapshotView(Map<Long, ChunkSnapshot> chunks, Map<Long, Set<Integer>> marks, long origin, TimberScanner.Cell originCell, int minY, int maxY) {
            this.chunks = chunks; this.marks = marks; this.origin = origin; this.originCell = originCell; this.minY = minY; this.maxY = maxY;
        }

        @Override
        public TimberScanner.Cell at(int x, int y, int z) {
            if (y < minY || y >= maxY) return TimberScanner.Cell.of(Material.VOID_AIR);
            if (TimberScanner.pack(x, y, z) == origin) return originCell;
            long key = chunkKey(x >> 4, z >> 4);
            ChunkSnapshot snapshot = chunks.get(key);
            if (snapshot == null) return null;
            Set<Integer> marked = marks.get(key);
            return cell(snapshot.getBlockData(x & 15, y, z & 15),
                    marked != null && marked.contains(TreeFireGuard.pack(x, y, z)));
        }
    }

    private void tick() {
        try {
            int snapshotBudget = SNAPSHOTS_PER_TICK;
            for (Scan scan : new ArrayList<>(scans)) {
                if (scan.async) continue;
                if (!scan.player.isOnline()) { scans.remove(scan); limits.release(scan.world.getUID(), scan.playerId); continue; }
                while (!scan.pending.isEmpty() && snapshotBudget > 0) {
                    long[] chunk = scan.pending.removeLast();
                    int cx = (int) chunk[0], cz = (int) chunk[1];
                    if (!scan.world.isChunkLoaded(cx, cz)) continue;
                    var loaded = scan.world.getChunkAt(cx, cz);
                    long key = chunkKey(cx, cz);
                    scan.snapshots.put(key, loaded.getChunkSnapshot(false, false, false));
                    Set<Integer> marked = new HashSet<>();
                    if (plugin.fireGuard() != null) marked.addAll(plugin.fireGuard().markedPositions(loaded));
                    GlowstoneGuard glow = plugin.glowstoneGuard();
                    if (glow != null) marked.addAll(glow.trackedPositions(loaded));
                    if (!marked.isEmpty()) scan.marks.put(key, marked);
                    snapshotBudget--;
                }
                if (scan.pending.isEmpty()) launch(scan);
            }
            int checks = options.checksPerTick(), blocks = options.blocksPerTick();
            for (Fell fell : new ArrayList<>(fells)) {
                int[] budgets = {checks, blocks};
                fell.step(budgets);
                checks = budgets[0];
                blocks = budgets[1];
            }
        } catch (RuntimeException failure) {
            DebugMessages.high("[CustomTrees/Timber] Tick failed: " + failure);
        }
    }

    private void launch(Scan scan) {
        scan.async = true;
        TimberOptions o = options;
        TimberScanner.Limits scanLimits = new TimberScanner.Limits(o.maxBlocks(), scan.radius, o.maxHeight(), o.minLeaves(), o.naturalGround(), o.maxRadius());
        SnapshotView view = new SnapshotView(Map.copyOf(scan.snapshots), Map.copyOf(scan.marks),
                TimberScanner.pack(scan.ox, scan.oy, scan.oz), scan.origin, scan.world.getMinHeight(), scan.world.getMaxHeight());
        boolean lookup = o.coreProtectCheck() || o.onlyGeneratedTrees();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TimberScanner.Result result;
            TimberGuards.History history = TimberGuards.History.UNKNOWN;
            try {
                result = TimberScanner.scan(view, scan.ox, scan.oy, scan.oz, scan.trigger, scanLimits);
                if (result.tree() && lookup && coreProtect.available()) {
                    List<Long> sample = new ArrayList<>(result.slice());
                    sample.addAll(TimberMath.sample(result.logs(), 24));
                    history = coreProtect.history(scan.world, TimberMath.sample(sample, 48));
                }
            } catch (RuntimeException failure) {
                DebugMessages.high("[CustomTrees/Timber] Tree scan failed: " + failure);
                result = TimberScanner.Result.rejected(TimberScanner.Verdict.NOT_A_LOG);
            }
            TimberScanner.Result done = result;
            TimberGuards.History known = history;
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> scanned(scan, done, known));
        });
    }

    private void scanned(Scan scan, TimberScanner.Result result, TimberGuards.History history) {
        TimberOptions o = options;
        if (result.verdict() == TimberScanner.Verdict.OUTSIDE_VIEW && scan.radius < o.maxRadius()) {
            scan.async = false;
            scan.plan(o.maxRadius());
            return;
        }
        scans.remove(scan);
        Player player = scan.player;
        if (!result.tree() || !player.isOnline()
                || !TimberGuards.originAccepted(result.markedBlocks(), history, o.coreProtectCheck(), o.onlyGeneratedTrees())) {
            limits.release(scan.world.getUID(), scan.playerId);
            refused++;
            if (player.isOnline() && (result.verdict() == TimberScanner.Verdict.TOO_LARGE || result.verdict() == TimberScanner.Verdict.OUTSIDE_VIEW))
                plugin.actionBar(player, "timber.too-large");
            return;
        }
        if (o.effectiveMode(result.size(), personalMode(player)) == null) { limits.release(scan.world.getUID(), scan.playerId); return; }
        long origin = TimberScanner.pack(scan.ox, scan.oy, scan.oz);
        if (scan.trigger == TimberOptions.Trigger.PERCENT) {
            ChopProgress.Cut cut = progress.start(scan.world.getUID(), result, origin, o.trunkPercent(),
                    System.currentTimeMillis(), o.percentMemorySeconds() * 1000L);
            plugin.actionBar(player, "timber.progress", "chopped", cut.chopped(), "required", cut.required);
            if (!cut.ready()) { limits.release(scan.world.getUID(), scan.playerId); return; }
        }
        fell(player, scan.tool, scan.world, result);
    }

    // ---- fell ----

    private enum Phase { CHECK, SPAWN, REMOVE, ANIMATE, DONE }

    private record Approved(long pos, BlockData data, int part, boolean drops, boolean logToCoreProtect) {}

    /** Starts the fell of a scanned tree; the limit slot is already taken. */
    private void fell(Player player, ItemStack tool, World world, TimberScanner.Result plan) {
        TimberOptions o = options;
        TimberOptions.Mode mode = o.effectiveMode(plan.size(), personalMode(player));
        if (mode == null) { limits.release(world.getUID(), player.getUniqueId()); return; }
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        int damage = creative ? 0 : TimberDrops.damage(Math.max(0, plan.logs().size() - 1), o.durabilityPerLog());
        if (o.spareTool() && damage > 0 && !toolSurvives(tool, damage)) {
            plugin.actionBar(player, "timber.tool-spared");
            limits.release(world.getUID(), player.getUniqueId());
            return;
        }
        fells.add(new Fell(player, tool, world, plan, mode, creative, o));
    }

    private static boolean toolSurvives(ItemStack tool, int damage) {
        ItemMeta meta = tool.getItemMeta();
        if (!(meta instanceof Damageable damageable)) return true;
        int max = damageable.hasMaxDamage() ? damageable.getMaxDamage() : tool.getType().getMaxDurability();
        // +1: the chopped log's own wear is applied after the break event.
        return TimberDrops.toolSurvives(max, damageable.getDamage() + 1, damage,
                tool.getEnchantmentLevel(Enchantment.UNBREAKING), meta.isUnbreakable());
    }

    private final class Fell {
        final Player player;
        final UUID playerId;
        final String playerName;
        final ItemStack tool;
        final World world;
        final TimberScanner.Result plan;
        final TimberOptions.Mode mode;
        final boolean creative;
        final TimberOptions o;
        final List<Long> order = new ArrayList<>();
        final List<Region> regions;
        final Map<Long, Boolean> cellDecision = new HashMap<>();
        final boolean batched;
        final List<Approved> approved = new ArrayList<>();
        final List<Block> unmark = new ArrayList<>();
        final Map<Long, List<ItemStack>> drops = new HashMap<>();
        final int leafEvery;
        Phase phase = Phase.CHECK;
        int index, leafIndex, logsRemoved;
        // fall animation
        final List<BlockDisplay> displays = new ArrayList<>();
        final List<float[]> offsets = new ArrayList<>();
        double pivotX, pivotY, pivotZ, finalAngle;
        double[] axis;
        int fallTick, fallTotal;
        List<double[]> impact = List.of();

        Fell(Player player, ItemStack tool, World world, TimberScanner.Result plan, TimberOptions.Mode mode, boolean creative, TimberOptions o) {
            this.player = player;
            this.playerId = player.getUniqueId();
            this.playerName = player.getName();
            this.tool = tool;
            this.world = world;
            this.plan = plan;
            this.mode = mode;
            this.creative = creative;
            this.o = o;
            order.addAll(plan.logs());
            order.addAll(plan.crown());
            order.addAll(plan.attachments());
            batched = plan.crown().size() > PER_BLOCK_CHECK_LIMIT;
            leafEvery = Math.max(1, (int) Math.ceil(plan.crown().size() / (double) LEAF_DROP_SAMPLE));
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (long p : order) {
                minX = Math.min(minX, TimberScanner.x(p)); maxX = Math.max(maxX, TimberScanner.x(p));
                minZ = Math.min(minZ, TimberScanner.z(p)); maxZ = Math.max(maxZ, TimberScanner.z(p));
            }
            List<Region> found;
            try { found = order.isEmpty() ? List.of() : plugin.core().area().regions().intersecting(world.getUID(), minX, minZ, maxX, maxZ); }
            catch (RuntimeException failure) { found = List.of(); }
            regions = found;
            pivotX = plan.pivotX();
            pivotY = plan.cutY();
            pivotZ = plan.pivotZ();
        }

        int part(int i) {
            int logs = plan.logs().size(), crown = plan.crown().size();
            return i < logs ? 0 : i < logs + crown ? 1 : 2;
        }

        boolean matches(int part, Material type) {
            return switch (part) {
                case 0 -> plan.family().equals(TimberBlocks.family(type));
                case 1 -> TimberBlocks.leaves(type) || TimberBlocks.cap(type);
                default -> TimberBlocks.attachment(type) || type == Material.GLOWSTONE;
            };
        }

        boolean regionDenied(int x, int y, int z) {
            if (regions.isEmpty()) return false;
            List<Region> containing = new ArrayList<>(2);
            for (Region region : regions) if (region.shape().contains(world.getUID(), x, y, z)) containing.add(region);
            return TimberGuards.regionDenies(containing, o.protectedRegionKinds());
        }

        void step(int[] budgets) {
            switch (phase) {
                case CHECK -> check(budgets);
                case SPAWN -> spawn();
                case REMOVE -> remove(budgets);
                case ANIMATE -> animate();
                case DONE -> { }
            }
        }

        void check(int[] budgets) {
            if (!player.isOnline()) { abort(); return; }
            int visits = budgets[0] * 4;
            while (index < order.size() && budgets[0] > 0 && visits-- > 0) {
                int i = index++;
                long p = order.get(i);
                int part = part(i), x = TimberScanner.x(p), y = TimberScanner.y(p), z = TimberScanner.z(p);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                Block block = world.getBlockAt(x, y, z);
                if (!matches(part, block.getType())) continue;
                if (regionDenied(x, y, z)) {
                    if (part == 0) { plugin.actionBar(player, "timber.protected"); abort(); return; }
                    continue;
                }
                long cell = TimberScanner.pack(x >> 2, y >> 2, z >> 2);
                Boolean decided = batched && part == 1 ? cellDecision.get(cell) : null;
                if (decided != null) {
                    if (decided) approved.add(new Approved(p, block.getBlockData(), part, true, true));
                    continue;
                }
                budgets[0]--;
                BlockData data = block.getBlockData();
                TimberBreakEvent event = new TimberBreakEvent(block, player);
                Bukkit.getPluginManager().callEvent(event);
                boolean allowed = !event.isCancelled() && block.getType() == data.getMaterial();
                if (batched && part == 1) cellDecision.put(cell, allowed);
                if (!allowed) {
                    if (part == 0) { plugin.actionBar(player, "timber.protected"); abort(); return; }
                    continue;
                }
                approved.add(new Approved(p, data, part, event.isDropItems(), false));
            }
            if (index < order.size()) return;
            if (approved.stream().noneMatch(a -> a.part() == 0)) { abort(); return; }
            index = 0;
            if (mode == TimberOptions.Mode.DROP) {
                approved.sort((a, b) -> Integer.compare(TimberScanner.y(a.pos()), TimberScanner.y(b.pos())));
                phase = Phase.REMOVE;
            } else phase = Phase.SPAWN;
        }

        /** Fall: obstacle check, displays in place of the tree, then the real blocks go. */
        void spawn() {
            TimberMath.Blocked blocked = blockedCheck();
            double[] direction = TimberMath.fallDirection(player.isOnline() ? player.getLocation().getX() : pivotX,
                    player.isOnline() ? player.getLocation().getZ() : pivotZ,
                    player.isOnline() ? player.getLocation().getYaw() : 0f, pivotX, pivotZ);
            axis = TimberMath.axis(direction);
            List<double[]> points = new ArrayList<>();
            for (Approved a : TimberMath.sample(approved, OBSTACLE_SAMPLE))
                points.add(new double[] {TimberScanner.x(a.pos()) + 0.5 - pivotX, TimberScanner.y(a.pos()) + 0.5 - pivotY,
                        TimberScanner.z(a.pos()) + 0.5 - pivotZ});
            finalAngle = TimberMath.stopAngle(points, new double[] {pivotX, pivotY, pivotZ}, axis, 90, 5, blocked);
            fallTotal = TimberMath.fallTicks(finalAngle, o.fallTicks());
            List<double[]> landing = new ArrayList<>();
            double radians = Math.toRadians(finalAngle);
            for (double[] point : TimberMath.sample(points, 64)) {
                double[] moved = TimberMath.rotate(point, axis, radians);
                landing.add(new double[] {pivotX + moved[0], pivotY + moved[1] + 0.5, pivotZ + moved[2]});
            }
            impact = landing;

            int bx = (int) Math.floor(pivotX), bz = (int) Math.floor(pivotZ);
            List<int[]> blocks = new ArrayList<>(approved.size());
            for (Approved a : approved)
                blocks.add(new int[] {TimberScanner.x(a.pos()) - bx, TimberScanner.y(a.pos()) - (int) pivotY, TimberScanner.z(a.pos()) - bz, a.part()});
            Location at = new Location(world, pivotX, pivotY, pivotZ);
            float view = (float) Math.clamp(plan.blocks() / 400.0, 1.0, 4.0);
            for (TimberMath.Voxel voxel : TimberMath.proxy(blocks, o.maxDisplays())) {
                float[] offset = {(float) (voxel.x() + bx - pivotX), voxel.y(), (float) (voxel.z() + bz - pivotZ), voxel.size()};
                BlockData data = approved.get(voxel.source()).data();
                try {
                    BlockDisplay display = world.spawn(at, BlockDisplay.class, d -> {
                        d.setPersistent(false);
                        d.addScoreboardTag(TimberGuards.DISPLAY_TAG);
                        d.setBlock(data);
                        d.setViewRange(view);
                        d.setInterpolationDelay(0);
                        d.setInterpolationDuration(0);
                        d.setTransformation(transform(offset, 0));
                    });
                    displays.add(display);
                    offsets.add(offset);
                } catch (RuntimeException failure) {
                    DebugMessages.mid("[CustomTrees/Timber] Display not spawned: " + failure);
                    break;
                }
            }
            displaysSpawned += displays.size();
            world.playSound(at, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.8f, 0.5f);
            phase = Phase.REMOVE;
        }

        TimberMath.Blocked blockedCheck() {
            Set<Long> own = new HashSet<>(approved.size() * 2);
            for (Approved a : approved) own.add(a.pos());
            int minY = world.getMinHeight(), maxY = world.getMaxHeight();
            return (x, y, z) -> {
                if (y < minY) return true;
                if (y >= maxY || own.contains(TimberScanner.pack(x, y, z))) return false;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return true;
                Material type = world.getBlockAt(x, y, z).getType();
                return !(TimberBlocks.open(type) || type == Material.WATER || type == Material.LAVA || TimberBlocks.cap(type));
            };
        }

        Transformation transform(float[] offset, double degrees) {
            float[] q = TimberMath.quaternion(axis == null ? new double[] {1, 0, 0} : axis, Math.toRadians(degrees));
            Quaternionf rotation = new Quaternionf(q[0], q[1], q[2], q[3]);
            Vector3f translation = new Vector3f(offset[0], offset[1], offset[2]).rotate(rotation);
            return new Transformation(translation, rotation, new Vector3f(offset[3], offset[3], offset[3]), new Quaternionf());
        }

        void remove(int[] budgets) {
            int perTick = budgets[1];
            if (mode == TimberOptions.Mode.DROP) perTick = Math.min(perTick, Math.max(32, (int) Math.ceil(approved.size() / 20.0)));
            int removedNow = 0;
            while (index < approved.size() && removedNow < perTick) {
                Approved a = approved.get(index++);
                if (removeOne(a)) removedNow++;
                if (mode == TimberOptions.Mode.DROP && removedNow % 12 == 1) {
                    Location at = new Location(world, TimberScanner.x(a.pos()) + 0.5, TimberScanner.y(a.pos()) + 0.5, TimberScanner.z(a.pos()) + 0.5);
                    world.spawnParticle(Particle.BLOCK, at, 6, .3, .3, .3, 0, a.data());
                }
            }
            budgets[1] -= removedNow;
            flushUnmark();
            if (mode == TimberOptions.Mode.DROP && removedNow > 0 && ThreadLocalRandom.current().nextInt(3) == 0) {
                Approved a = approved.get(Math.max(0, index - 1));
                world.playSound(new Location(world, TimberScanner.x(a.pos()), TimberScanner.y(a.pos()), TimberScanner.z(a.pos())),
                        a.part() == 0 ? Sound.BLOCK_WOOD_BREAK : Sound.BLOCK_AZALEA_LEAVES_BREAK, 0.8f, 0.8f);
            }
            if (index < approved.size()) return;
            if (mode == TimberOptions.Mode.FALL) { phase = Phase.ANIMATE; fallTick = 0; }
            else finish();
        }

        boolean removeOne(Approved a) {
            int x = TimberScanner.x(a.pos()), y = TimberScanner.y(a.pos()), z = TimberScanner.z(a.pos());
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
            Block block = world.getBlockAt(x, y, z);
            if (block.getType() != a.data().getMaterial()) return false;
            collectDrops(block, a);
            block.setType(Material.AIR, false);
            if (a.logToCoreProtect()) coreProtect.logRemoval(playerName, block, a.data());
            unmark.add(block);
            if (a.part() == 0) logsRemoved++;
            blocksRemoved++;
            return true;
        }

        void flushUnmark() {
            if (unmark.isEmpty()) return;
            if (plugin.fireGuard() != null) plugin.fireGuard().unmarkAll(unmark);
            unmark.clear();
        }

        void collectDrops(Block block, Approved a) {
            if (creative || !a.drops() || (a.part() == 1 && !o.leafDrops())) return;
            int scale = 1;
            if (a.part() == 1 && leafEvery > 1) {
                if (leafIndex++ % leafEvery != 0) return;
                scale = leafEvery;
            }
            Collection<ItemStack> items;
            try { items = block.getDrops(tool, player.isOnline() ? player : null); }
            catch (RuntimeException failure) { return; }
            long bucket = mode == TimberOptions.Mode.DROP
                    ? TimberScanner.pack(Math.floorDiv(block.getX(), DROP_CELL), 0, Math.floorDiv(block.getZ(), DROP_CELL)) : 0L;
            List<ItemStack> list = drops.computeIfAbsent(bucket, ignored -> new ArrayList<>());
            for (ItemStack item : items) {
                if (item == null || item.getType().isAir() || item.getAmount() <= 0) continue;
                ItemStack copy = item.clone();
                if (scale > 1) copy.setAmount(item.getAmount() * scale);
                list.add(copy);
            }
            if (list.size() > MERGE_AT) {
                List<ItemStack> merged = merge(list);
                list.clear();
                list.addAll(merged);
            }
        }

        void animate() {
            fallTick++;
            if (fallTick == 1 || fallTick % 2 == 1) {
                int target = Math.min(fallTotal, fallTick + 1);
                double degrees = TimberMath.angleAt(target, fallTotal, finalAngle);
                for (int i = 0; i < displays.size(); i++) {
                    BlockDisplay display = displays.get(i);
                    if (!display.isValid()) continue;
                    display.setInterpolationDelay(0);
                    display.setInterpolationDuration(2);
                    display.setTransformation(transform(offsets.get(i), degrees));
                }
            }
            if (fallTick >= fallTotal + 2) finish();
        }

        /** Impact (fall) or end of the collapse (drop): particles, sound, items, tool wear; displays go. */
        void finish() {
            if (phase == Phase.DONE) return;
            phase = Phase.DONE;
            fells.remove(this);
            limits.release(world.getUID(), playerId);
            felled++;
            for (BlockDisplay display : displays) if (display.isValid()) display.remove();
            displays.clear();
            flushUnmark();
            BlockData logData = approved.isEmpty() ? Material.OAK_LOG.createBlockData() : approved.getFirst().data();
            boolean replantedByPlayer = o.replant() && plan.size() == TimberOptions.SizeClass.SMALL && !approved.isEmpty() && replant(approved.getFirst().data().getMaterial());
            ForestRegrowth regrowth = plugin.regrowth();
            if (regrowth != null && !approved.isEmpty() && regrowth.felled(world, plan.size(), plan.logs(), approved.getFirst().data().getMaterial(), replantedByPlayer)
                    && player.isOnline()) plugin.actionBar(player, "regrowth.queued");
            if (mode == TimberOptions.Mode.FALL) {
                List<double[]> spots = impact.isEmpty() ? List.of(new double[] {pivotX, pivotY + 0.5, pivotZ}) : impact;
                for (double[] spot : TimberMath.sample(spots, 24)) {
                    Location at = new Location(world, spot[0], spot[1], spot[2]);
                    world.spawnParticle(Particle.BLOCK, at, 10, .6, .4, .6, 0, logData);
                    world.spawnParticle(Particle.POOF, at, 2, .5, .3, .5, 0.02);
                }
                Location crash = new Location(world, spots.get(spots.size() / 2)[0], spots.get(spots.size() / 2)[1], spots.get(spots.size() / 2)[2]);
                world.playSound(crash, Sound.BLOCK_WOOD_BREAK, 1.4f, 0.55f);
                world.playSound(crash, Sound.ENTITY_PLAYER_BIG_FALL, 1.2f, 0.6f);
                world.playSound(crash, Sound.BLOCK_AZALEA_LEAVES_BREAK, 1.0f, 0.7f);
                List<ItemStack> all = new ArrayList<>();
                for (List<ItemStack> list : drops.values()) all.addAll(list);
                deliver(merge(all), spots);
            } else {
                for (Map.Entry<Long, List<ItemStack>> bucket : drops.entrySet()) {
                    double x = (TimberScanner.x(bucket.getKey()) + 0.5) * DROP_CELL, z = (TimberScanner.z(bucket.getKey()) + 0.5) * DROP_CELL;
                    deliver(merge(bucket.getValue()), List.of(new double[] {x, groundY(x, z), z}));
                }
            }
            drops.clear();
            int damage = TimberDrops.damage(Math.max(0, logsRemoved), o.durabilityPerLog());
            if (!creative && damage > 0 && player.isOnline()) {
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand.getType() == tool.getType()) {
                    try { plugin.core().compat().gameplay().damageItemStack(player, EquipmentSlot.HAND, damage); }
                    catch (RuntimeException | LinkageError failure) { DebugMessages.mid("[CustomTrees/Timber] Tool wear failed: " + failure); }
                }
            }
        }

        /**
         * Puts a sapling on each spot of the lowest trunk layer (one, or four for a 2x2 trunk) when the ground fits
         * and the player has enough: saplings from this tree's own drops first, then from the inventory (creative: free).
         * Every sapling fires a {@link TimberReplantEvent}; one refusal takes the whole replanting back.
         */
        boolean replant(Material log) {
            Material sapling = TimberReplant.sapling(log);
            List<Long> spots = TimberReplant.spots(plan.logs());
            if (sapling == null || spots.isEmpty() || !player.isOnline() || !player.getWorld().equals(world)) return false;
            BlockData data = sapling.createBlockData();
            List<Block> blocks = new ArrayList<>(spots.size());
            for (long pos : spots) {
                int x = TimberScanner.x(pos), y = TimberScanner.y(pos), z = TimberScanner.z(pos);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
                Block block = world.getBlockAt(x, y, z);
                if (!block.getType().isAir() || !data.isSupported(block)) return false;
                blocks.add(block);
            }
            int need = blocks.size();
            if (!creative && countSaplings(sapling) < need) return false;
            List<org.bukkit.block.BlockState> placed = new ArrayList<>(need);
            for (Block block : blocks) {
                org.bukkit.block.BlockState before = block.getState();
                block.setBlockData(data, false);
                placed.add(before);
                TimberReplantEvent event = new TimberReplantEvent(block, before, new ItemStack(sapling), player);
                Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled() || !event.canBuild()) {
                    for (int i = placed.size() - 1; i >= 0; i--) placed.get(i).update(true, false);
                    return false;
                }
            }
            if (!creative) takeSaplings(sapling, need);
            replanted++;
            world.playSound(blocks.getFirst().getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_GRASS_PLACE, 0.8f, 1.0f);
            return true;
        }

        int countSaplings(Material sapling) {
            int count = 0;
            for (List<ItemStack> list : drops.values())
                for (ItemStack item : list) if (item.getType() == sapling && !item.hasItemMeta()) count += item.getAmount();
            for (ItemStack item : player.getInventory().getStorageContents())
                if (item != null && item.getType() == sapling && !item.hasItemMeta()) count += item.getAmount();
            return count;
        }

        void takeSaplings(Material sapling, int amount) {
            int left = amount;
            for (List<ItemStack> list : drops.values()) {
                for (Iterator<ItemStack> it = list.iterator(); it.hasNext() && left > 0; ) {
                    ItemStack item = it.next();
                    if (item.getType() != sapling || item.hasItemMeta()) continue;
                    int take = Math.min(left, item.getAmount());
                    left -= take;
                    if (take == item.getAmount()) it.remove(); else item.setAmount(item.getAmount() - take);
                }
            }
            if (left > 0) player.getInventory().removeItem(new ItemStack(sapling, left));
        }

        double groundY(double x, double z) {
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return pivotY + 0.5;
            for (int y = (int) pivotY + 2; y > Math.max(world.getMinHeight(), (int) pivotY - 24); y--) {
                Material below = world.getBlockAt(bx, y - 1, bz).getType();
                if (!TimberBlocks.open(below) && below != Material.WATER) return y + 0.2;
            }
            return pivotY + 0.5;
        }

        void deliver(List<ItemStack> stacks, List<double[]> spots) {
            List<ItemStack> left = stacks;
            if (o.intoInventory() && player.isOnline()) {
                left = new ArrayList<>(player.getInventory().addItem(stacks.toArray(ItemStack[]::new)).values());
            }
            int i = 0;
            for (ItemStack stack : left) {
                double[] spot = spots.get(i++ % spots.size());
                world.dropItem(new Location(world, spot[0], spot[1], spot[2]), stack);
            }
        }

        void abort() {
            if (phase == Phase.DONE) return;
            phase = Phase.DONE;
            fells.remove(this);
            limits.release(world.getUID(), playerId);
            refused++;
            for (BlockDisplay display : displays) if (display.isValid()) display.remove();
            displays.clear();
        }

        boolean touches(int cx, int cz) {
            return ((int) Math.floor(pivotX) >> 4) == cx && ((int) Math.floor(pivotZ) >> 4) == cz;
        }
    }

    private static List<ItemStack> merge(List<ItemStack> items) {
        return TimberDrops.merge(items, ItemStack::isSimilar, ItemStack::getAmount, ItemStack::getMaxStackSize,
                (item, amount) -> { ItemStack copy = item.clone(); copy.setAmount(amount); return copy; });
    }

    /** Ends a fell at once: unchecked fells are dropped, started ones remove the rest and land immediately. */
    private void finishNow(Fell fell) {
        if (fell.phase == Phase.CHECK || fell.phase == Phase.DONE) { fell.abort(); return; }
        if (fell.phase == Phase.SPAWN) { fell.abort(); return; }
        if (fell.phase == Phase.REMOVE) {
            while (fell.index < fell.approved.size()) fell.removeOne(fell.approved.get(fell.index++));
        }
        fell.finish();
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        int cx = event.getChunk().getX(), cz = event.getChunk().getZ();
        for (Fell fell : new ArrayList<>(fells)) if (fell.world.equals(event.getWorld()) && fell.touches(cx, cz)) finishNow(fell);
        TimberGuards.cleanup(List.of(event.getChunk().getEntities()));
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        List<Entity> stale = new ArrayList<>();
        for (Entity entity : event.getEntities()) if (TimberGuards.isTimberDisplay(entity)) stale.add(entity);
        if (stale.isEmpty()) return;
        // Not ours if a running fell owns it.
        Set<Entity> running = new HashSet<>();
        for (Fell fell : fells) running.addAll(fell.displays);
        stale.removeIf(running::contains);
        TimberGuards.cleanup(stale);
    }
}
