package at.esoren.customtrees;

import Vystorm.vystorm_core.compat.PlatformCompatInterface;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiCallbacks;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.schedule.TaskGroup;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Plugin side of the {@link Herbarium}: records forms grown by players, tells them about new forms and ranks, runs
 * optional rank commands, shows the chest menu and stores {@code herbarium.yml} (atomic writes, at most once a minute
 * and on shutdown). A broken file is moved aside, never overwritten. Server thread.
 */
final class HerbariumFeature {
    static final String FILE = "herbarium.yml";
    static final String MENU = "customtrees:herbarium";
    private static final int SUMMARY_SLOT = 4, TOP_SLOT = 45, PLANTING_SLOT = 53;
    /** Menu slots of the 13 plants: trees in the second and third row, fungi in the fourth. */
    private static final int[] PLANT_SLOTS = {10, 11, 12, 13, 14, 15, 16, 21, 23, 29, 30, 32, 33};

    private final CustomTreesPlugin plugin;
    private final Lang lang;
    private final Path file;
    private Herbarium data = new Herbarium();
    /** herbarium.yml was unreadable at start: read-only, never saved until restart. */
    private boolean broken;
    private volatile Herbarium.Options options = Herbarium.Options.read(new YamlConfiguration());
    private TaskGroup tasks;

    HerbariumFeature(CustomTreesPlugin plugin, Lang lang) {
        this.plugin = plugin;
        this.lang = lang;
        this.file = plugin.getDataFolder().toPath().resolve(FILE);
    }

    void start() {
        load();
        tasks = plugin.core().tasks().of(plugin, "herbarium");
        tasks.timer(1200L, 1200L, this::save);
    }

    void stop() {
        if (tasks != null) tasks.close();
        save();
    }

    void apply(Herbarium.Options options) { this.options = options; }
    Herbarium.Options options() { return options; }
    boolean enabled() { return options.enabled(); }
    Herbarium data() { return data; }

    private void load() {
        Herbarium loaded = read(file, plugin.getLogger());
        broken = loaded == null;
        data = loaded == null ? new Herbarium() : loaded;
    }

    /**
     * Reads {@code file}; a missing file is an empty herbarium. An unreadable file is moved aside as
     * {@code herbarium.yml.broken-<date-time>} and null is returned: the caller then never saves (read-only until restart),
     * so the collection is never overwritten with an empty one, even when the move fails.
     */
    static Herbarium read(Path file, java.util.logging.Logger logger) {
        if (!Files.isRegularFile(file)) return new Herbarium();
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            return Herbarium.fromYaml(yaml);
        } catch (IOException | InvalidConfigurationException | RuntimeException broken) {
            Path aside = file.resolveSibling(file.getFileName() + ".broken-"
                    + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            String kept = file.getFileName().toString();
            try { Files.move(file, aside); kept = aside.getFileName().toString(); } catch (IOException ignored) { /* stays in place */ }
            logger.severe(file.getFileName() + " could not be read (" + broken + "). It was kept as " + kept
                    + "; the herbarium is not saved until the next restart. Fix that file, rename it back to "
                    + file.getFileName() + " and restart, otherwise the herbarium starts empty next time.");
            return null;
        }
    }

    /** Writes herbarium.yml if something changed. */
    void save() {
        if (broken || !data.dirty()) return;
        try {
            Files.createDirectories(file.getParent());
            plugin.core().data().files().write(file, data.toYaml().saveToString().getBytes(StandardCharsets.UTF_8));
            data.saved();
        } catch (IOException | RuntimeException failure) {
            DebugMessages.high("[CustomTrees] herbarium.yml could not be saved: " + failure);
        }
    }

    /** A sapling formation grew for {@code player}; {@code luminous} also records the luminous form. */
    void grown(Player player, Herbarium.Plant plant, Herbarium.Stage stage, boolean luminous) {
        if (player == null || plant == null || stage == null) return;
        record(player, plant, stage);
        if (luminous) record(player, plant, Herbarium.Stage.LUMINOUS);
    }

    /** A world tree or fungus of {@code width} grew (completely or partly) for {@code player}. */
    void worldGrown(Player player, Material plantMaterial, int width) {
        Herbarium.Plant plant = Herbarium.Plant.of(plantMaterial);
        if (player == null || plant == null) return;
        record(player, plant, plant.fungus() ? Herbarium.Stage.fungus(width) : width == 10 ? Herbarium.Stage.WORLD : null);
    }

    private void record(Player player, Herbarium.Plant plant, Herbarium.Stage stage) {
        Herbarium.Options current = options;
        if (!current.enabled() || stage == null || !player.hasPermission("customtrees.herbarium")) return;
        Herbarium.Recorded recorded = data.record(player.getUniqueId(), player.getName(), plant, stage);
        if (!recorded.added()) return;
        plugin.core().compat().platform().sendMessage(player, lang.component(player, "herbarium.new-entry", "plant", plantName(player, plant),
                        "stage", stageName(player, stage), "count", recorded.found(), "total", Herbarium.TOTAL)
                .append(Component.text(" ")).append(lang.component(player, "herbarium.open")
                        .clickEvent(ClickEvent.runCommand("/customtrees herbarium"))));
        Herbarium.Rank rank = recorded.rankUp();
        if (rank == null) return;
        lang.send(player, "herbarium.rank-up", "rank", rankName(player, rank));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        if (current.announceRanks()) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other != player) lang.send(other, "herbarium.rank-broadcast", "player", player.getName(), "rank", rankName(other, rank));
            }
        }
        for (String command : current.rankCommands().getOrDefault(rank, List.of())) {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", player.getName()));
            } catch (RuntimeException failure) {
                DebugMessages.high("[CustomTrees] Herbarium rank command failed (" + command + "): " + failure);
            }
        }
    }

    String plantName(CommandSender viewer, Herbarium.Plant plant) { return lang.plain(viewer, "herbarium.plant." + plant.id()); }
    String stageName(CommandSender viewer, Herbarium.Stage stage) { return lang.plain(viewer, "herbarium.stage." + stage.id); }
    String rankName(CommandSender viewer, Herbarium.Rank rank) { return lang.plain(viewer, "herbarium.rank." + rank.id()); }

    /** {@code /customtrees herbarium [player|top]}. */
    void command(CommandSender sender, String[] args) {
        if (!enabled()) { lang.send(sender, "feature-off"); return; }
        if (!sender.hasPermission("customtrees.herbarium")) { lang.send(sender, "command.no-permission"); return; }
        if (args.length > 2) { lang.send(sender, "herbarium.usage"); return; }
        if (args.length == 2 && args[1].equalsIgnoreCase("top")) { sendTop(sender); return; }
        if (!(sender instanceof Player player)) {
            if (args.length == 1) { lang.send(sender, "herbarium.usage"); return; }
            sendSummary(sender, args[1]);
            return;
        }
        if (args.length == 1) { open(player, player.getUniqueId(), player.getName()); return; }
        Player online = Bukkit.getPlayerExact(args[1]);
        UUID target = online != null ? online.getUniqueId() : data.find(args[1]);
        if (target == null) { lang.send(sender, "herbarium.unknown-player", "player", args[1]); return; }
        open(player, target, online != null ? online.getName() : data.name(target));
    }

    private void sendSummary(CommandSender sender, String name) {
        UUID target = data.find(name);
        if (target == null) { lang.send(sender, "herbarium.unknown-player", "player", name); return; }
        int found = data.found(target);
        lang.send(sender, "herbarium.summary", "player", data.name(target), "rank", rankName(sender, Herbarium.Rank.of(found)));
        lang.send(sender, "herbarium.summary-progress", "count", found, "total", Herbarium.TOTAL);
    }

    void sendTop(CommandSender sender) {
        List<Herbarium.Standing> top = data.top(10);
        if (top.isEmpty()) { lang.send(sender, "herbarium.top-empty"); return; }
        lang.send(sender, "herbarium.top-header", "total", Herbarium.TOTAL);
        for (int i = 0; i < top.size(); i++) {
            Herbarium.Standing standing = top.get(i);
            lang.send(sender, "herbarium.top-line", "place", i + 1, "player", standing.name(), "count", standing.found(),
                    "rank", rankName(sender, Herbarium.Rank.of(standing.found())));
        }
    }

    void suggest(Player player, String input) {
        if (!player.hasPermission("customtrees.herbarium")) { lang.send(player, "command.no-permission"); return; }
        if (!enabled()) { lang.send(player, "feature-off"); return; }
        Herbarium.Plant plant = null;
        if (input != null) {
            for (Herbarium.Plant candidate : Herbarium.Plant.available())
                if (candidate.id().equalsIgnoreCase(input)) plant = candidate;
            if (plant == null) { lang.send(player, "herbarium.next-unknown", "plant", input); return; }
        }
        Herbarium.Suggestion next = data.next(player.getUniqueId(), plant);
        if (next == null) { lang.send(player, "herbarium.next-complete"); return; }
        lang.send(player, "herbarium.next-form", "plant", plantName(player, next.plant()),
                "stage", stageName(player, next.stage()));
        lang.send(player, "herbarium.recipe." + next.recipe(), "count", next.plants(), "width", next.width());
        lang.send(player, "herbarium.next-rules");
    }

    boolean usable(Player player) { return enabled() && player.hasPermission("customtrees.herbarium"); }

    /** Opens the collection through Panels, with the original read-only chest as fallback. */
    void open(Player viewer, UUID target, String targetName) {
        if (!usable(viewer)) { lang.send(viewer, enabled() ? "command.no-permission" : "feature-off"); return; }
        if (!plugin.openHerbariumPanel(viewer, target, targetName)) openChest(viewer, target, targetName);
    }

    void openChest(Player viewer, UUID target, String targetName) {
        if (!usable(viewer)) { lang.send(viewer, enabled() ? "command.no-permission" : "feature-off"); return; }
        String name = targetName == null ? viewer.getName() : targetName;
        Inventory inventory = plugin.core().gui().createInventory(plugin, null, viewer.getUniqueId(), 54,
                lang.text(viewer, "herbarium.title", "player", name), new GuiCallbacks() {
                    @Override public void click(InventoryClickEvent event) {
                        event.setCancelled(true);
                        if (event.getClickedInventory() != plugin.core().compat().inventory().top(event.getView())) return;
                        int slot = event.getRawSlot();
                        for (int i = 0; i < Herbarium.Plant.available().length; i++) {
                            if (slot != PLANT_SLOTS[i]) continue;
                            String plant = Herbarium.Plant.available()[i].id();
                            Bukkit.getScheduler().runTask(plugin, () -> { viewer.closeInventory(); suggest(viewer, plant); });
                            return;
                        }
                        if (slot == TOP_SLOT) Bukkit.getScheduler().runTask(plugin, () -> { viewer.closeInventory(); sendTop(viewer); });
                        else if (slot == PLANTING_SLOT && plugin.planting() != null && plugin.planting().usable(viewer))
                            Bukkit.getScheduler().runTask(plugin, () -> plugin.planting().openMenu(viewer));
                    }
                });
        int found = data.found(target);
        Herbarium.Rank rank = Herbarium.Rank.of(found);
        List<Component> summary = new ArrayList<>();
        summary.add(line(viewer, "herbarium.summary-progress", "count", found, "total", Herbarium.TOTAL));
        Herbarium.Rank next = rank.next();
        summary.add(next == null ? line(viewer, "herbarium.summary-max")
                : line(viewer, "herbarium.summary-next", "rank", rankName(viewer, next), "needed", next.needed()));
        inventory.setItem(SUMMARY_SLOT, item(plugin.core().compat().platform(), Material.BOOK, lang.component(viewer, "herbarium.summary", "player", name,
                "rank", rankName(viewer, rank)), summary, rank == Herbarium.Rank.KEEPER));
        Herbarium.Plant[] plants = Herbarium.Plant.available();
        for (int i = 0; i < plants.length; i++) {
            Herbarium.Plant plant = plants[i];
            List<Component> lore = new ArrayList<>();
            int plantFound = data.found(target, plant);
            lore.add(line(viewer, "herbarium.plant-progress", "count", plantFound, "total", plant.stages().size()));
            lore.add(line(viewer, "herbarium.next-click"));
            for (Herbarium.Stage stage : plant.stages()) {
                lore.add(line(viewer, data.has(target, plant, stage) ? "herbarium.found" : "herbarium.missing",
                        "stage", stageName(viewer, stage)));
            }
            ItemStack icon = item(plugin.core().compat().platform(), plant.icon, Component.text(plantName(viewer, plant)), lore, plantFound == plant.stages().size());
            icon.setAmount(Math.max(1, plantFound));
            inventory.setItem(PLANT_SLOTS[i], icon);
        }
        List<Component> topLore = new ArrayList<>();
        List<Herbarium.Standing> top = data.top(5);
        for (int i = 0; i < top.size(); i++) {
            topLore.add(line(viewer, "herbarium.top-line", "place", i + 1, "player", top.get(i).name(),
                    "count", top.get(i).found(), "rank", rankName(viewer, Herbarium.Rank.of(top.get(i).found()))));
        }
        if (top.isEmpty()) topLore.add(line(viewer, "herbarium.top-empty"));
        inventory.setItem(TOP_SLOT, item(plugin.core().compat().platform(), Material.GOLDEN_HOE, lang.component(viewer, "herbarium.top-button"), topLore, false));
        if (plugin.planting() != null && plugin.planting().usable(viewer)) {
            inventory.setItem(PLANTING_SLOT, item(plugin.core().compat().platform(), Material.OAK_SAPLING, lang.component(viewer, "herbarium.planting-button"),
                    List.of(line(viewer, "planting.menu-description")), false));
        }
        viewer.openInventory(inventory);
    }

    private Component line(Player viewer, String key, Object... placeholders) {
        return lang.component(viewer, key, placeholders).decoration(TextDecoration.ITALIC, false);
    }

    static ItemStack item(PlatformCompatInterface platform, Material material, Component name, List<Component> lore, boolean glint) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        platform.displayName(meta, name.decoration(TextDecoration.ITALIC, false));
        platform.lore(meta, lore);
        if (glint) meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    /** Status line of {@code /customtrees status}. */
    static String statusLine(Herbarium.Options options, int collectors, int keepers) {
        return String.format(Locale.ROOT, "CustomTrees herbarium: enabled=%s, forms=%d, collectors=%d, all-forms=%d, announce-ranks=%s, rank-commands=%d",
                options.enabled(), Herbarium.TOTAL, collectors, keepers, options.announceRanks(),
                options.rankCommands().values().stream().mapToInt(List::size).sum());
    }

    String status() { return statusLine(options, data.collectors(), data.keepers()); }
}
