package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructurePos;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiCallbacks;
import Vystorm.vystorm_core.lang.Lang;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Planting helper: a player picks a square or hedge row (menu or {@code /customtrees plant 3x3}); the next sapling, propagule,
 * mushroom or fungus they place fills the selected formation from their inventory. Every spot must be free with fitting
 * ground, otherwise nothing is planted and the blocked spots are shown. Each plant fires a normal
 * {@link BlockPlaceEvent}, so protection plugins and CoreProtect see it; one refusal undoes the whole square.
 * Untagged plants only (tagged profile saplings stay one by one). Server thread.
 */
final class PlantingHelper implements Listener {
    static final String MENU = "customtrees:planting";
    private static final int[] SIZE_SLOTS = {10, 12, 14, 16};
    private static final int OFF_SLOT = 31;
    private static final List<Integer> HEDGE_LENGTHS = List.of(4, 8, 16);

    private record Armed(PlantingStencil.Pattern pattern, long expiresMillis) {}

    private final CustomTreesPlugin plugin;
    private final Lang lang;
    private final Map<UUID, Armed> armed = new HashMap<>();
    private volatile PlantingStencil.Options options = PlantingStencil.Options.read(new org.bukkit.configuration.file.YamlConfiguration());
    private boolean firing;
    private long squares, plants;

    PlantingHelper(CustomTreesPlugin plugin, Lang lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    void apply(PlantingStencil.Options options) {
        this.options = options;
        if (!options.enabled()) armed.clear();
        else armed.values().removeIf(entry -> !entry.pattern().allowed(options));
    }

    boolean usable(Player player) { return options.enabled() && player.hasPermission("customtrees.plant"); }

    /** Current selection, or null after expiry. */
    Armed selection(Player player) {
        Armed entry = armed.get(player.getUniqueId());
        if (entry == null) return null;
        if (System.currentTimeMillis() > entry.expiresMillis()) { armed.remove(player.getUniqueId()); return null; }
        return entry;
    }

    void forget(UUID player) { armed.remove(player); }

    String status() {
        long now = System.currentTimeMillis();
        int active = (int) armed.values().stream().filter(entry -> entry.expiresMillis() >= now).count();
        return PlantingStencil.statusLine(options, squares, plants, active);
    }

    /** {@code /customtrees plant [2x2|3x3|5x5|10x10|hedgeN|off]}; without an argument the menu. */
    void command(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { lang.send(sender, "command.players-only"); return; }
        if (!options.enabled()) { lang.send(player, "planting.disabled"); return; }
        if (!player.hasPermission("customtrees.plant")) { lang.send(player, "command.no-permission"); return; }
        if (args.length == 1) { openMenu(player); return; }
        if (args.length != 2) { lang.send(player, "planting.usage"); return; }
        if (args[1].equalsIgnoreCase("off")) { disarm(player); return; }
        if (args[1].equalsIgnoreCase("status")) {
            Armed current = selection(player);
            if (current == null) { lang.send(player, "planting.status-none"); return; }
            var pattern = current.pattern();
            lang.send(player, "planting.status-selection", "pattern", lang.plain(player, pattern.hedge() ? "planting.hedge-size" : "planting.size", "size", pattern.size()),
                    "count", pattern.hedge() ? pattern.size() : pattern.size() * pattern.size(),
                    "seconds", Math.max(0, (current.expiresMillis() - System.currentTimeMillis() + 999) / 1000),
                    "mode", lang.plain(player, options.keepActive() ? "planting.status-repeat" : "planting.status-once"));
            return;
        }
        PlantingStencil.Pattern pattern = PlantingStencil.Pattern.parse(args[1]);
        if (pattern == null || !pattern.allowed(options)) { lang.send(player, "planting.usage"); return; }
        arm(player, pattern);
    }

    void arm(Player player, int size) { arm(player, new PlantingStencil.Pattern(size, false)); }

    void arm(Player player, PlantingStencil.Pattern pattern) {
        if (!usable(player) || !pattern.allowed(options)) { lang.send(player, "planting.disabled"); return; }
        armed.put(player.getUniqueId(), new Armed(pattern, System.currentTimeMillis() + options.timeoutMillis()));
        plugin.core().compat().platform().sendMessage(player, lang.component(player, pattern.key("armed"), "size", pattern.size()).append(Component.text(" "))
                .append(plugin.core().message().chat().button(lang.ref("planting.off-button"), lang.ref("planting.off"),
                        "customtrees.plant", this::disarm)));
    }

    void disarm(Player player) {
        armed.remove(player.getUniqueId());
        lang.send(player, "planting.disarmed");
    }

    void openMenu(Player player) {
        if (!usable(player)) { lang.send(player, "planting.disabled"); return; }
        if (!plugin.openPlantingPanel(player)) openChest(player);
    }

    PlantingStencil.Options options() { return options; }

    PlantingStencil.Pattern activePattern(Player player) {
        Armed current = selection(player);
        return current == null ? null : current.pattern();
    }

    List<PlantingStencil.Pattern> menuPatterns() {
        List<PlantingStencil.Pattern> patterns = new ArrayList<>();
        for (int size : options.sizes()) patterns.add(new PlantingStencil.Pattern(size, false));
        for (int length : HEDGE_LENGTHS) {
            var pattern = new PlantingStencil.Pattern(length, true);
            if (pattern.allowed(options)) patterns.add(pattern);
        }
        return List.copyOf(patterns);
    }

    /** Original chest, used when Core Panels cannot open. */
    void openChest(Player player) {
        if (!usable(player)) { lang.send(player, "planting.disabled"); return; }
        List<Integer> sizes = options.sizes();
        Inventory inventory = plugin.core().gui().createInventory(plugin, null, player.getUniqueId(), 36,
                lang.text(player, "planting.title"), new GuiCallbacks() {
                    @Override public void click(InventoryClickEvent event) {
                        event.setCancelled(true);
                        if (event.getClickedInventory() != plugin.core().compat().inventory().top(event.getView())) return;
                        int slot = event.getRawSlot();
                        Runnable action = null;
                        for (int i = 0; i < sizes.size(); i++) {
                            int size = sizes.get(i);
                            if (SIZE_SLOTS[i] == slot) action = () -> arm(player, size);
                        }
                        for (int i = 0; i < HEDGE_LENGTHS.size(); i++) {
                            var pattern = new PlantingStencil.Pattern(HEDGE_LENGTHS.get(i), true);
                            if (slot == 20 + i * 2 && pattern.allowed(options)) action = () -> arm(player, pattern);
                        }
                        if (slot == OFF_SLOT) action = () -> disarm(player);
                        if (action == null) return;
                        Runnable chosen = action;
                        Bukkit.getScheduler().runTask(plugin, () -> { player.closeInventory(); chosen.run(); });
                    }
                });
        Armed active = selection(player);
        for (int i = 0; i < sizes.size(); i++) {
            int size = sizes.get(i);
            List<Component> lore = new ArrayList<>();
            lore.add(line(player, "planting.size-count", "count", size * size));
            lore.add(line(player, "planting.size-grows", "result", lang.plain(player, "planting.result." + size + "x" + size)));
            if (active != null && active.pattern().equals(new PlantingStencil.Pattern(size, false))) lore.add(line(player, "planting.size-active"));
            ItemStack icon = HerbariumFeature.item(plugin.core().compat().platform(), icon(size), lang.component(player, "planting.size", "size", size), lore, active != null && active.pattern().equals(new PlantingStencil.Pattern(size, false)));
            icon.setAmount(size);
            inventory.setItem(SIZE_SLOTS[i], icon);
        }
        for (int i = 0; i < HEDGE_LENGTHS.size(); i++) {
            var pattern = new PlantingStencil.Pattern(HEDGE_LENGTHS.get(i), true);
            if (!pattern.allowed(options)) continue;
            inventory.setItem(20 + i * 2, HerbariumFeature.item(plugin.core().compat().platform(), Material.OAK_LEAVES,
                    lang.component(player, "planting.hedge-size", "size", pattern.size()),
                    List.of(line(player, "planting.hedge-how"), line(player, "planting.size-count", "count", pattern.size())),
                    active != null && active.pattern().equals(pattern)));
        }
        inventory.setItem(OFF_SLOT, HerbariumFeature.item(plugin.core().compat().platform(), Material.BARRIER, lang.component(player, "planting.off"),
                List.of(line(player, "planting.how")), false));
        player.openInventory(inventory);
    }

    static Material icon(int size) {
        return switch (size) {
            case 2 -> Material.BIRCH_SAPLING;
            case 3 -> Material.SPRUCE_SAPLING;
            case 5 -> Material.DARK_OAK_SAPLING;
            default -> Material.OAK_SAPLING;
        };
    }

    private Component line(Player player, String key, Object... placeholders) {
        return lang.component(player, key, placeholders).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Block break and place go through Core's stages, so Core decides when they run among all Vystorm plugins.
     * The stencil fills the rest of the square one tick after a placement that really happened and never touches
     * the event: OBSERVE, the final outcome, never a cancelled event.
     */
    void handleBlockEvents(org.bukkit.plugin.Plugin owner, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(owner, BlockPlaceEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::placed);
    }

    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void placed(BlockPlaceEvent event) {
        if (firing || event instanceof TimberReplantEvent || !options.enabled() || event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        Armed selected = selection(player);
        Block block = event.getBlock();
        Material plant = block.getType();
        if (selected == null || !selected.pattern().accepts(plant) || event.getItemInHand().getType() != plant) return;
        if (!player.hasPermission("customtrees.plant")) return;
        if (event.getItemInHand().hasItemMeta()) { plugin.actionBar(player, "planting.tagged"); return; }
        BlockFace facing = player.getFacing();
        StructurePos origin = new StructurePos(block.getX(), block.getY(), block.getZ());
        World world = block.getWorld();
        // Next tick: the placed plant is final and the hand item already used up.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.getWorld().equals(world) && selection(player) == selected && usable(player) && selected.pattern().allowed(options)
                    && world.isChunkLoaded(origin.x() >> 4, origin.z() >> 4)
                    && world.getBlockAt(origin.x(), origin.y(), origin.z()).getType() == plant) {
                fill(player, world, origin, plant, facing, selected.pattern());
            }
        });
    }

    private void fill(Player player, World world, StructurePos origin, Material plant, BlockFace facing, PlantingStencil.Pattern pattern) {
        int size = pattern.size();
        BlockData data = plant.createBlockData();
        List<StructurePos> cells = pattern.cells(origin, facing.getModX(), facing.getModZ());
        PlantingStencil.Plan plan = PlantingStencil.plan(cells, cell -> state(world, cell, plant, data));
        if (!plan.ready()) {
            lang.send(player, pattern.key("blocked"), "count", plan.blocked().size(), "size", size);
            for (StructurePos cell : plan.blocked()) {
                player.spawnParticle(Particle.DUST, cell.x() + .5, cell.y() + .3, cell.z() + .5, 6, .25, .2, .25, 0,
                        new Particle.DustOptions(org.bukkit.Color.RED, 1.2f));
            }
            return;
        }
        boolean free = player.getGameMode() == GameMode.CREATIVE;
        int needed = plan.free().size();
        int available = free ? needed : count(player.getInventory(), plant);
        if (available < needed) {
            lang.send(player, pattern.key("missing"), "missing", needed - available, "size", size);
            return;
        }
        List<BlockState> placed = new ArrayList<>();
        firing = true;
        try {
            for (StructurePos cell : plan.free()) {
                Block block = world.getBlockAt(cell.x(), cell.y(), cell.z());
                BlockState before = block.getState();
                block.setBlockData(data, false);
                placed.add(before);
                BlockPlaceEvent event = new BlockPlaceEvent(block, before, block.getRelative(BlockFace.DOWN),
                        new ItemStack(plant), player, true, EquipmentSlot.HAND);
                Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled() || !event.canBuild()) {
                    for (int i = placed.size() - 1; i >= 0; i--) placed.get(i).update(true, false);
                    lang.send(player, "planting.not-allowed");
                    return;
                }
            }
        } finally {
            firing = false;
        }
        if (!free) take(player.getInventory(), plant, needed);
        if (!pattern.hedge()) squares++;
        plants += needed;
        world.playSound(player.getLocation(), "minecraft:item.bone_meal.use", 0.8f, 1.1f);
        lang.send(player, pattern.key("done"), "size", size, "count", needed);
        if (!options.keepActive()) armed.remove(player.getUniqueId());
    }

    private PlantingStencil.Cell state(World world, StructurePos cell, Material plant, BlockData data) {
        if (cell.y() <= world.getMinHeight() || cell.y() >= world.getMaxHeight()
                || !world.isChunkLoaded(cell.x() >> 4, cell.z() >> 4)) return PlantingStencil.Cell.BLOCKED;
        Block block = world.getBlockAt(cell.x(), cell.y(), cell.z());
        if (block.getType() == plant) return PlantingStencil.Cell.SAME;
        boolean empty = block.getType().isAir() || (plugin.core().compat().gameplay().isReplaceable(block) && !block.isLiquid());
        return empty && data.isSupported(block) ? PlantingStencil.Cell.FREE : PlantingStencil.Cell.BLOCKED;
    }

    /** Plain (untagged) items of {@code plant} in the inventory. */
    private static int count(PlayerInventory inventory, Material plant) {
        int count = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && item.getType() == plant && !item.hasItemMeta()) count += item.getAmount();
        }
        return count;
    }

    private static void take(PlayerInventory inventory, Material plant, int amount) {
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length && amount > 0; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType() != plant || item.hasItemMeta()) continue;
            int used = Math.min(amount, item.getAmount());
            amount -= used;
            if (used == item.getAmount()) { inventory.setItem(slot, null); continue; }
            item.setAmount(item.getAmount() - used);
            inventory.setItem(slot, item);
        }
    }

    /** Arguments of {@code /customtrees plant} for tab completion. */
    static List<String> sizeNames() {
        List<String> names = new ArrayList<>();
        for (int size : PlantingStencil.SIZES) names.add(size + "x" + size);
        for (int length : HEDGE_LENGTHS) names.add("hedge" + length);
        names.add("off");
        return names;
    }
}
