package at.esoren.customtrees;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.commands.Arg;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.lang.Lang;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.Locale;
import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.World;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;

public final class CustomTreesPlugin extends JavaPlugin implements Listener {
    /** Oldest supported Vystorm Core release (placeholders through api.placeholders()). */
    static final String MIN_CORE_VERSION = "0.65.0";
    private WorldTreeGrowth worldTrees;
    private WorldTreeSettings worldTreeSettings;
    private volatile TreeShaking.Options shakingOptions;
    private TreeShaking shaking;
    private HerbariumFeature herbarium;
    private PlantingHelper planting;
    private TreesPanels panels;
    private TimberFeature timber;
    TimberFeature timber() { return timber; }
    private ForestRegrowth regrowth;
    private FruitTrees fruitTrees;
    ForestRegrowth regrowth() { return regrowth; }
    GlowstoneGuard glowstoneGuard() { return saplingListener == null ? null : saplingListener.glowstoneGuard(); }
    HerbariumFeature herbarium() { return herbarium; }
    PlantingHelper planting() { return planting; }
    TreeShaking.Options shakingOptions() { return shakingOptions; }
    WorldTreeGrowth worldTrees() { return worldTrees; }
    WorldTreeSettings worldTreeSettings() { return worldTreeSettings; }
    TreeSettings treeSettings() { return currentSettings(); }
    private API core;
    private Lang lang;
    private volatile ActiveTreeSettings activeSettings;
    private GeneratedTreeListener generatedTreeListener;
    private GiantSaplingListener saplingListener;
    private TreeSettingsEditor settingsEditor;
    private TreeFireGuard fireGuard;
    private GrowthPlanting plantings;
    private GardenTrees gardens;
    TreeFireGuard fireGuard() { return fireGuard; }
    GrowthPlanting plantings() { return plantings; }
    GardenTrees gardens() { return gardens; }
    Lang lang() { return lang; }
    /** Optional web page (Core 0.24.0+ with the web platform API), else null. */
    private TreesWeb web;
    /** Pages of the web tree guide that /customtrees guide [page] may open (sub-paths of /app/trees). */
    static final List<String> GUIDE_PAGES = List.of("guide", "species", "admin");
    /** Core menu id of the tutorial registered via registerTutorial (tutorial.yml). */
    static final String TUTORIAL_MENU = "customtrees:tutorial";
    static final String SETTINGS_SECTION = "customtrees:" + TreeSettingsEditor.SECTION_ID;
    private static final long TUTORIAL_HINT_COOLDOWN_MS = 10 * 60_000L;
    private final Map<UUID, Long> tutorialHints = new ConcurrentHashMap<>();
    /** Every feature switch as read in applySettings; null (= defaults) until then and in tests that mock the plugin. */
    private volatile Feature.Switches switches;
    /** The switches online players last got their command list for (null before the first registration). */
    private Feature.Switches commandSwitches;
    /** Whether the tutorial was registered at start (tutorial.enabled). */
    private boolean tutorialRegistered;

    /** Whether a feature (and trees.enabled) is on; static so classes holding a mocked plugin read the real field. */
    static boolean enabled(CustomTreesPlugin plugin, Feature feature) {
        Feature.Switches current = plugin == null ? null : plugin.switches;
        return (current == null ? Feature.Switches.read(null) : current).on(feature);
    }

    boolean on(Feature feature) { return enabled(this, feature); }

    /** True (and the shared message sent) when the feature is off; names the needed switch when that one is off. */
    boolean off(CommandSender sender, Feature feature) {
        if (on(feature)) return false;
        Feature.Switches current = switches;
        Feature missing = current != null && !current.off().contains(feature) ? blocking(current, feature) : null;
        if (missing != null) lang.send(sender, "feature-needs", "switch", missing.path, "name", lang.plain(sender, missing.label()));
        else lang.send(sender, "feature-off");
        return true;
    }

    /** The switch that keeps an own-switched-on feature off (trees.enabled or the one it needs), or null. */
    private static Feature blocking(Feature.Switches current, Feature feature) {
        if (feature != Feature.TREES && !current.on(Feature.TREES)) return Feature.TREES;
        for (Feature needed = feature.needs; needed != null; needed = needed.needs)
            if (current.off().contains(needed)) return needed;
        return null;
    }

    /**
     * config.yml as the features see it: every switched-off feature (also everything while trees.enabled is off) reads
     * as false, so the existing option readers switch it off without knowing the switches. The file is not changed.
     */
    static org.bukkit.configuration.file.YamlConfiguration effective(org.bukkit.configuration.file.FileConfiguration config, Feature.Switches switches) {
        org.bukkit.configuration.file.YamlConfiguration copy = new org.bukkit.configuration.file.YamlConfiguration();
        try { copy.loadFromString(config.saveToString()); }
        catch (org.bukkit.configuration.InvalidConfigurationException impossible) { throw new IllegalStateException(impossible); }
        for (Feature feature : Feature.values()) if (!switches.on(feature)) copy.set(feature.path, false);
        return copy;
    }

    @Override
    public void onEnable() {
        if (!CoreApi.atLeast(MIN_CORE_VERSION)) {
            getLogger().severe("CustomTrees requires Vystorm Core " + MIN_CORE_VERSION
                    + " or newer; startup aborted. Please update Vystorm Core.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        core = CoreApi.api().orElseThrow();
        core.admin().updates().spigot(this, 139850);
        DebugMessages.mid("[CustomTreesPlugin 40] Vystorm Core structure generator and inventory-use API connected");
        saveDefaultConfig();
        YamlCheck.readConfig(this, false);   // a broken config.yml: reported with line and column, jar defaults, never saved over
        lang = core.lang().register(this);
        applyLanguage();
        if (PaperCompat.material("PALE_OAK_SAPLING") == null)
            getLogger().info(lang.text(Bukkit.getConsoleSender(), "compat.missing-pale-oak"));
        // Before the first settings load, which hands them their options.
        herbarium = new HerbariumFeature(this, lang);
        planting = new PlantingHelper(this, lang);
        timber = new TimberFeature(this, lang);
        regrowth = new ForestRegrowth(this);
        fruitTrees = new FruitTrees(this);
        // tutorial.enabled is read once: Core lists tutorials when a plugin starts
        tutorialRegistered = getConfig().getBoolean(Feature.TUTORIAL.path, true);
        if (tutorialRegistered) core.registerTutorial(this);
        settingsEditor = new TreeSettingsEditor(this::getConfig, this::saveCheckedConfig, this::applySettings, lang,
                (player, message) -> core.compat().platform().sendMessage(player, message.append(Component.text(" "))
                        .append(core.message().chat().settings(lang.ref("action.settings"), SETTINGS_SECTION))));
        String startupError = settingsEditor.load();
        if (startupError != null) {
            DebugMessages.high("[CustomTreesPlugin 40] CustomTrees cannot start: " + startupError);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        fireGuard = new TreeFireGuard(this, () -> currentSettings().fireProtectTrunks(), () -> currentSettings().fireProtectCrowns());
        plantings = new GrowthPlanting(this);
        gardens = new GardenTrees(this);
        worldTrees = new WorldTreeGrowth(this);
        shaking = new TreeShaking(this);
        TreeShaking.registerLoot(core.loot(), this);
        herbarium.start();
        // Core orders the handlers of the shared events; inside one stage they keep the order of these lines
        var stages = core.priority().stages();
        Bukkit.getPluginManager().registerEvents(planting, this);
        planting.handleBlockEvents(this, stages);
        registerPlayerMenus();
        Bukkit.getPluginManager().registerEvents(fireGuard, this);
        fireGuard.handleBlockEvents(this, stages);
        fireGuard.handleWorldEvents(this, stages);
        plantings.handleBlockEvents(this, stages);
        plantings.handleWorldEvents(this, stages);
        Bukkit.getPluginManager().registerEvents(gardens, this);
        gardens.handleBlockEvents(this, stages);
        gardens.handleWorldEvents(this, stages);
        generatedTreeListener = GeneratedTreeListener.withActiveSettings(this, () -> activeSettings);
        saplingListener = GiantSaplingListener.withActiveSettings(this, () -> activeSettings);
        Bukkit.getPluginManager().registerEvents(generatedTreeListener, this);
        generatedTreeListener.handleBlockEvents(this, stages);
        Bukkit.getPluginManager().registerEvents(saplingListener, this);
        saplingListener.glowstoneGuard().handleBlockEvents(this, stages);
        saplingListener.glowstoneGuard().handleWorldEvents(this, stages);
        timber.start();
        timber.handleBlockEvents(this, stages);
        regrowth.start();
        fruitTrees.start();
        Bukkit.getPluginManager().registerEvents(fruitTrees, this);
        // clicks: shaking, fruit marking and picking, then the growth catalyst, as their priorities ordered them before
        shaking.handleInteract(this, stages);
        fruitTrees.handleInteract(this, stages);
        saplingListener.handleInteract(this, stages);
        Bukkit.getPluginManager().registerEvents(this, this);
        // Worlds are not loaded yet at STARTUP; check the names on the first tick.
        Bukkit.getScheduler().runTask(this, this::warnUnknownWorlds);
        try {
            core.settings().register(this, settingsEditor.section());
        } catch (RuntimeException failure) {
            DebugMessages.mid("[CustomTreesPlugin 58] Core settings menu unavailable: " + failure);
        }
        try { registerPlaceholders(core.placeholders()); }
        catch (RuntimeException failure) { getLogger().log(Level.WARNING, "Placeholders not registered", failure); }
        registerCommands();
        try {
            TreesConfigEditor.register(core.settings().config(), this, lang, this::applyEditedConfig);
            declareSwitches();
        } catch (RuntimeException failure) {
            DebugMessages.high("[CustomTreesPlugin] Config editor registration failed: " + failure);
        }
        // Optional web page (guide.enabled); if it cannot be registered CustomTrees runs without it.
        if (on(Feature.GUIDE)) startWeb();
        DebugMessages.mid("[CustomTreesPlugin 49] Custom trees, four architecture variants, shrubs, hedges, glowstone/coal growth, protected 3x3/5x5 trees and one-time Iris chunk post-processing are ready.");
    }

    /** Herbarium and planting helper in /vmenu; the herbarium also in Core's player menu. */
    private void registerPlayerMenus() {
        registerPanels();
        try {
            core.gui().registerMenu(this, "herbarium", lang.ref("herbarium.menu-title"), Material.OAK_SAPLING,
                    p -> herbarium.enabled() && p.hasPermission("customtrees.herbarium"),
                    p -> herbarium.open(p, p.getUniqueId(), p.getName()),
                    MenuCategory.CHARACTER.group(), lang.ref("herbarium.menu-description"));
            core.gui().registerMenu(this, "planting", lang.ref("planting.menu-title"), Material.DARK_OAK_SAPLING,
                    planting::usable, planting::openMenu, MenuCategory.BUILDING.group(), lang.ref("planting.menu-description"));
        } catch (RuntimeException failure) {
            DebugMessages.mid("[CustomTreesPlugin] Menu entries unavailable: " + failure);
        }
        try {
            HerbariumPlayerMenu.register(core.gui().playerMenu(), core.compat().platform(), this, herbarium, lang);
        } catch (RuntimeException failure) {
            // No player menu entry; /customtrees herbarium <player> still works.
        }
    }

    /** Panels are optional; the chest menus stay if they cannot be registered. */
    private void registerPanels() {
        try {
            TreesPanels created = new TreesPanels(this);
            try {
                created.register();
                panels = created;
            } catch (RuntimeException failure) {
                created.close();
                throw failure;
            }
        } catch (RuntimeException failure) {
            DebugMessages.high("[CustomTrees] Panels unavailable; using chest menus: " + failure);
        }
    }

    boolean openHerbariumPanel(Player viewer, UUID target, String name) {
        if (panels != null) {
            try { return panels.openHerbarium(viewer, target, name); }
            catch (RuntimeException failure) {
                DebugMessages.high("[CustomTrees] Herbarium panel could not open: " + failure);
            }
        }
        return false;
    }

    boolean openPlantingPanel(Player player) {
        if (panels != null) {
            try { return panels.openPlanting(player); }
            catch (RuntimeException failure) {
                DebugMessages.high("[CustomTrees] Planting panel could not open: " + failure);
            }
        }
        return false;
    }

    /** A validated configuration becomes active (start, reload, settings menu or web). */
    private void applySettings(TreeSettings loaded, BiomeGrowthProfiles profiles) {
        Feature.Switches current = Feature.Switches.read(getConfig());
        switches = current;
        // the switches as false values: the readers below and TreeSettings switch the features off by themselves
        org.bukkit.configuration.file.FileConfiguration config = current.off().isEmpty() ? getConfig() : effective(getConfig(), current);
        TreeSettings settings = config == getConfig() ? loaded : TreeSettings.load(config);
        WorldTreeSettings worldSettings = WorldTreeSettings.load(config);
        worldTreeSettings = worldSettings;
        shakingOptions = TreeShaking.Options.read(config);
        if (herbarium != null) herbarium.apply(Herbarium.Options.read(config));
        if (planting != null) planting.apply(PlantingStencil.Options.read(config));
        if (timber != null) timber.apply(TimberOptions.read(config));
        if (regrowth != null) regrowth.apply(config);
        if (fruitTrees != null) fruitTrees.apply(config);
        activeSettings = new ActiveTreeSettings(settings, current.on(Feature.BIOME_PROFILES) ? profiles : BiomeGrowthProfiles.EMPTY);
        applySwitches();
        TreesWeb page = web;
        if (page != null) page.publishGuide();
    }

    /**
     * After a settings load: when a switch changed, online players get their command list again (the tree asks the
     * switches at every completion, so it is not registered again); the web guide is registered or removed.
     */
    private void applySwitches() {
        if (commandSwitches != null && !commandSwitches.equals(switches)) {
            commandSwitches = switches;
            try { core.command().refresh(); }
            catch (RuntimeException failure) { DebugMessages.high("[CustomTrees] command lists could not be sent again: " + failure); }
        }
        if (commandSwitches == null) return; // still starting: onEnable registers the web page itself
        if (on(Feature.GUIDE) && web == null) startWeb();
        else if (!on(Feature.GUIDE) && web != null) { web.unregister(); web = null; }
    }

    /** Registers /customtrees once; the subcommands of switched-off features are hidden from help and tab completion. */
    private void registerCommands() {
        commandSwitches = switches == null ? Feature.Switches.read(null) : switches;
        core.command().register(this, lang, commandSpec(this));
    }

    /** Optional web page; if it cannot be registered CustomTrees runs without it. */
    private void startWeb() {
        try {
            TreesWeb page = new TreesWeb(this, lang, this::currentSettings);
            web = page;
            page.register();
        } catch (RuntimeException failure) {
            web = null;
            DebugMessages.mid("[CustomTreesPlugin] Web page unavailable: " + failure);
        }
    }

    /** The namespace {@code trees}: herbarium, breeding cooldown and grown counters (empty while switched off). */
    void registerPlaceholders(Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface registry) {
        TreesPlaceholders.register(registry, this, new TreesPlaceholders.Source() {
            @Override public boolean on(Feature feature) { return CustomTreesPlugin.this.on(feature); }
            @Override public Herbarium herbarium() { return herbarium == null ? null : herbarium.data(); }
            @Override public String rankName(Player viewer, Herbarium.Rank rank) { return herbarium.rankName(viewer, rank); }
            @Override public long breedCooldown(Player player) {
                Long ready = player.getPersistentDataContainer().get(SaplingBreeding.COOLDOWN, org.bukkit.persistence.PersistentDataType.LONG);
                return TreesPlaceholders.secondsLeft(ready == null ? 0L : ready, System.currentTimeMillis());
            }
            @Override public long[] grown() {
                GiantSaplingListener listener = saplingListener;
                return listener == null ? null : new long[] {listener.regularTreesGrown(), listener.giantTreesGrown(),
                        listener.colossalTreesGrown(), listener.shrubsGrown(), listener.hedgesGrown()};
            }
        }, key -> lang == null ? key : lang.ref(key));
    }

    /** Every switch in /vadmin features with its one-line description in the admin's language. */
    private void declareSwitches() { declareSwitches(core.settings().config(), this, lang); }

    /** What Core receives for {@code /vadmin features}, after {@link TreesConfigEditor#register} (FeatureLabelsTest runs both). */
    static void declareSwitches(Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface config, org.bukkit.plugin.Plugin plugin, Vystorm.vystorm_core.lang.Lang lang) {
        for (Feature feature : Feature.values()) {
            // A null label would also drop the label TreesConfigEditor set: every switch gets its own name (1.47.1).
            config.feature(plugin, feature.path, lang.ref(feature.label()), lang.ref(feature.description()));
            if (feature.needsRestart()) config.activation(plugin, feature.path, Vystorm.vystorm_core._uiMethods.settings.SettingPolicy.Activation.RESTART);
        }
    }

    private TreeSettings currentSettings() {
        ActiveTreeSettings active = activeSettings;
        return active == null ? null : active.settings();
    }

    private void applyLanguage() {
        lang.setLanguage(getConfig().getString("language", "auto"));
    }

    /** Localized action bar for a player (no-op before the language bundle exists, e.g. in unit tests). */
    void actionBar(Player player, String key, Object... placeholders) {
        if (lang != null && player != null) lang.actionBar(player, key, placeholders);
    }

    void message(Player player, String key, Object... placeholders) {
        if (lang != null && player != null) lang.send(player, key, placeholders);
    }

    String localized(Player player, String key) {
        return lang == null || player == null ? key : lang.plain(player, key);
    }

    public API core() {
        return Objects.requireNonNull(core, "Vystorm Core API is not bound");
    }

    @Override
    public void onDisable() {
        if (panels != null) {
            try { panels.close(); }
            catch (RuntimeException failure) { DebugMessages.high("[CustomTrees] Panel cleanup failed: " + failure); }
            panels = null;
        }
        if (timber != null) timber.stop();
        if (regrowth != null) regrowth.stop();
        if (fruitTrees != null) fruitTrees.stop();
        if (worldTrees != null) worldTrees.close();
        if (herbarium != null) herbarium.stop();
        if (saplingListener != null) saplingListener.clearPendingReservations();
        if (web != null) { web.unregister(); web = null; }
        if (core != null) { core.gui().unregisterMenus(this); core.dialogs().removeAll(this); }
        if (core != null) DebugMessages.mid("[CustomTreesPlugin 58] CustomTrees stopped");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (panels != null) panels.forget(event.getPlayer().getUniqueId());
        tutorialHints.remove(event.getPlayer().getUniqueId());
        if (saplingListener != null) saplingListener.onPlayerQuit(event.getPlayer());
        if (shaking != null) shaking.forget(event.getPlayer().getUniqueId());
        if (planting != null) planting.forget(event.getPlayer().getUniqueId());
    }

    private void warnUnknownWorlds() {
        TreeSettings current = currentSettings();
        if (current == null) {
            return;
        }
        for (String name : current.enabledWorlds()) {
            boolean known = Bukkit.getWorlds().stream().map(World::getName)
                    .anyMatch(world -> world.equalsIgnoreCase(name));
            if (!known) {
                DebugMessages.high("[CustomTreesPlugin] enabled-worlds contains unknown world '" + name + "'");
            }
        }
    }

    /** Throttled chat hint to the tutorial after a failed growth attempt (the action bar keeps the reason). */
    void tutorialHint(Player player) {
        if (!tutorialRegistered) return; // tutorial.enabled was off at start
        if (!player.hasPermission("customtrees.grow")) return;
        long now = System.currentTimeMillis();
        Long last = tutorialHints.get(player.getUniqueId());
        if (last != null && now - last < TUTORIAL_HINT_COOLDOWN_MS) return;
        tutorialHints.put(player.getUniqueId(), now);
        Component hint = lang.component(player, "tutorial-hint")
                .append(core.message().chat().menu(lang.ref("action.tutorial"), TUTORIAL_MENU));
        if (web != null && web.available()) hint = hint.append(Component.text(" ")).append(guideButton(player, ""));
        core.compat().platform().sendMessage(player, hint);
    }

    /** Snapshot of what /customtrees status shows (command and web admin panel). Server thread. */
    TreesStatus status() {
        List<String> integrations = new ArrayList<>();
        for (String name : new String[] {"Iris", "CoreProtect", "SmoothTimber"}) {
            var other = Bukkit.getPluginManager().getPlugin(name);
            if (other != null && other.isEnabled()) integrations.add(name);
        }
        return new TreesStatus(getDescription().getVersion(), currentSettings(),
                saplingListener.regularTreesGrown(), saplingListener.giantTreesGrown(),
                saplingListener.colossalTreesGrown(), saplingListener.shrubsGrown(), saplingListener.hedgesGrown(),
                saplingListener.glowstoneTreesGrown(), saplingListener.coalTreesGrown(),
                saplingListener.glowstoneBlocksPlaced(), saplingListener.coreProtectLoggedBlocks(),
                generatedTreeListener.inspectedChunks(), generatedTreeListener.replacedTrees(),
                generatedTreeListener.queuedTrees(), integrations, saplingListener.speciesTreesGrown());
    }

    /**
     * Re-reads config.yml and the language files (/customtrees reload and the web reload button). Returns null or the
     * validation error; on an error the previous settings stay active. Server thread.
     */
    String reload() {
        return reload(null);
    }

    /**
     * The settings menu saves through here: a config.yml with a YAML error is never saved over (the in-memory config
     * then holds only the jar defaults). The change stays active until the next restart and the error is reported.
     */
    void saveCheckedConfig() {
        String problem = YamlCheck.problem(new java.io.File(getDataFolder(), "config.yml"));
        if (problem == null) { saveConfig(); return; }
        getLogger().severe("config.yml has a YAML error at " + problem + "; the change was not saved. Fix the file and reload.");
    }

    /** Config editor hook: {@code /customtrees reload}; a rejected value keeps the previous settings active. */
    private void applyEditedConfig() {
        String error = reload();
        if (error != null) throw new IllegalStateException(error);
    }

    String reload(String language) {
        // A config.yml with a YAML error is left as it is; the previous settings stay active (reported with line and column).
        String problem = YamlCheck.readConfig(this, true);
        if (problem != null) return lang == null ? "config.yml: " + problem : lang.plain(language == null ? lang.language(null) : language, "config-error", "problem", problem);
        applyLanguage();
        lang.reload();
        String error = settingsEditor.load(language == null ? lang.language(null) : language);
        if (error == null) {
            DebugMessages.mid("[CustomTreesPlugin 69] Configuration reloaded");
            warnUnknownWorlds();
            try {
                core.settings().changed(null);
            } catch (RuntimeException failure) {
                // The open settings pages are not refreshed.
            }
        } else {
            DebugMessages.mid("[CustomTreesPlugin 72] Configuration reload rejected: " + error);
        }
        return error;
    }

    // ---- command --------------------------------------------------------------------------------------------------

    /**
     * {@code /customtrees} as a Vystorm Core command tree (Core 0.45.0): names, aliases, usage and help come from
     * {@code commands.customtrees.*} in the language files ({@code /baumgarten setzling}, {@code /自定义树木 种植});
     * English always works. Without arguments it opens the tree guide like {@code guide}. {@code p} may be a mock or
     * {@code null} in tests that only look at names and help.
     */
    /**
     * Lists a subcommand in help and tab completion only while its switch (and trees.enabled) is on, asked at every
     * completion so a switch applies at once; while off it still answers the off message.
     */
    static CommandSpec.Builder gate(CommandSpec.Builder sub, CustomTreesPlugin p, Feature feature) {
        return p == null ? sub : sub.visibleWhen(() -> enabled(p, feature));
    }

    static CommandSpec commandSpec(CustomTreesPlugin p) {
        return CommandSpec.command("customtrees")
                .executes(ctx -> { if (!p.off(ctx.sender(), Feature.GUIDE)) p.commandGuide(ctx.sender(), null); })
                .sub("guide", s -> gate(s, p, Feature.GUIDE).aliases("web")
                        .arg(Arg.word("page").optional().suggests((ctx, prefix) -> {
                            List<String> pages = new ArrayList<>();
                            for (String page : GUIDE_PAGES)
                                if (!page.equals("admin") || ctx.sender().hasPermission(TreeSettingsEditor.PERMISSION)) pages.add(ctx.valueName(page));
                            return pages;
                        }))
                        .executes(ctx -> {
                            String input = ctx.get("page");
                            String page = input == null ? null : ctx.keyword(input, GUIDE_PAGES.toArray(String[]::new));
                            if (!p.off(ctx.sender(), Feature.GUIDE)) p.commandGuide(ctx.sender(), page != null ? page : input);
                        }))
                .sub("sapling", s -> gate(s, p, Feature.CROWN_CHOICE).permission("customtrees.grow").playerOnly()
                        .arg(Arg.word("form").optional().suggests((ctx, prefix) -> {
                            List<String> forms = new ArrayList<>();
                            for (var form : GrowthSapling.CrownForm.values()) forms.add(form.name().toLowerCase(Locale.ROOT));
                            return forms;
                        }))
                        .arg(Arg.word("size").optional().suggests((ctx, prefix) -> {
                            List<String> sizes = new ArrayList<>();
                            for (var size : GrowthSapling.GrowthClass.values()) sizes.add(size.name().toLowerCase(Locale.ROOT));
                            return sizes;
                        }))
                        .executes(ctx -> { if (!p.off(ctx.player(), Feature.CROWN_CHOICE)) p.commandSapling(ctx.player(), ctx.get("form"), ctx.get("size")); }))
                .sub("breed", s -> gate(s, p, Feature.BREEDING).permission("customtrees.breed").playerOnly().executes(ctx -> new SaplingBreeding(p).breed(ctx.player())))
                .sub("prune", s -> gate(s, p, Feature.GARDEN).permission("customtrees.garden").playerOnly().executes(ctx -> { if (!p.off(ctx.player(), Feature.GARDEN)) p.commandPrune(ctx.player()); }))
                .sub("plant", s -> gate(s, p, Feature.PLANTING).permission("customtrees.plant")
                        .arg(Arg.word("size").optional().suggests((ctx, prefix) -> {
                            List<String> sizes = new ArrayList<>();
                            for (String size : PlantingHelper.sizeNames()) sizes.add(size.equals("off") ? ctx.valueName("off") : size);
                            sizes.add(ctx.valueName("status"));
                            return sizes;
                        }))
                        .executes(ctx -> {
                            String size = ctx.get("size");
                            String keyword = size == null ? null : ctx.keyword(size, "off", "status");
                            if (!p.off(ctx.sender(), Feature.PLANTING)) p.commandPlant(ctx.sender(), keyword == null ? size : keyword);
                        }))
                .sub("herbarium", s -> gate(s, p, Feature.HERBARIUM).permission("customtrees.herbarium")
                        .arg(Arg.word("player").optional().suggests((ctx, prefix) -> {
                            List<String> options = new ArrayList<>(List.of(ctx.valueName("top")));
                            if (Bukkit.getServer() != null) for (Player online : Bukkit.getOnlinePlayers()) options.add(online.getName());
                            return options;
                        }))
                        .executes(ctx -> {
                            String target = ctx.get("player");
                            if (!p.off(ctx.sender(), Feature.HERBARIUM)) p.commandHerbarium(ctx.sender(), target != null && ctx.keyword(target, "top") != null ? "top" : target);
                        }))
                .sub("next", s -> gate(s, p, Feature.HERBARIUM).permission("customtrees.herbarium").playerOnly()
                        .arg(Arg.word("plant").optional().suggests((ctx, prefix) ->
                                java.util.Arrays.stream(Herbarium.Plant.available()).map(Herbarium.Plant::id).toList()))
                        .executes(ctx -> { if (!p.off(ctx.player(), Feature.HERBARIUM)) p.commandNext(ctx.player(), ctx.get("plant")); }))
                .sub("status", s -> s.permission(TreeSettingsEditor.PERMISSION).executes(ctx -> p.commandStatus(ctx.sender())))
                .sub("reload", s -> s.permission(TreeSettingsEditor.PERMISSION).executes(ctx -> p.commandReload(ctx.sender())))
                .build();
    }

    /** {@code /customtrees [guide|web] [page]}; {@code page} is an English page key, {@code null} or unknown input. */
    void commandGuide(CommandSender sender, String page) {
        String key = page == null ? "" : page.toLowerCase(Locale.ROOT);
        if (!key.isEmpty() && !GUIDE_PAGES.contains(key)) { lang.send(sender, "command.usage"); return; }
        if (!(sender instanceof Player player)) { lang.send(sender, "command.players-only"); return; }
        openGuide(player, key.equals("guide") ? "" : key);
    }

    /** {@code /customtrees sapling <form> <size>}: turns the single held vanilla sapling into a tagged one. */
    void commandSapling(Player player, String form, String size) {
        if (form == null || size == null) { message(player, "garden.sapling-usage"); return; }
        ItemStack held = player.getInventory().getItemInMainHand();
        var species = Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeSpecies.fromSapling(held.getType());
        if (species == null || held.getAmount() != 1 || held.hasItemMeta()) { message(player, "garden.single-sapling"); return; }
        try {
            var crown = GrowthSapling.CrownForm.parseCommand(form);
            var growth = GrowthSapling.GrowthClass.valueOf(size.toUpperCase(Locale.ROOT));
            // Transform the existing single vanilla sapling: no inventory insertion or free item grant.
            ItemStack tagged = GrowthSapling.create(core.compat().platform(), lang, lang.language(player), species, crown, growth);
            player.getInventory().setItemInMainHand(tagged);
            message(player, "garden.sapling-ready");
        } catch (IllegalArgumentException bad) { message(player, "garden.sapling-usage"); }
    }

    void commandPrune(Player player) { gardens.prune(player); }

    void commandNext(Player player, String plant) { herbarium.suggest(player, plant); }

    /** {@code /customtrees plant [size|off]}; {@code size} {@code null} opens the menu. */
    void commandPlant(CommandSender sender, String size) {
        planting.command(sender, size == null ? new String[]{"plant"} : new String[]{"plant", size});
    }

    /** {@code /customtrees herbarium [player|top]}. */
    void commandHerbarium(CommandSender sender, String target) {
        herbarium.command(sender, target == null ? new String[]{"herbarium"} : new String[]{"herbarium", target});
    }

    void commandReload(CommandSender sender) {
        String error = reload(lang.language(sender));
        if (error == null) {
            lang.send(sender, "command.reloaded");
            return;
        }
        Component message = lang.component(sender, "command.reload-failed", "error", error);
        if (sender instanceof Player player) {
            core.compat().platform().sendMessage(player, message.append(Component.text(" "))
                    .append(core.message().chat().settings(lang.ref("action.settings"), SETTINGS_SECTION)));
        } else {
            core.compat().platform().sendMessage(sender, message);
        }
    }

    void commandStatus(CommandSender sender) {
        TreeSettings current = currentSettings();
        String worlds = current.enabledWorlds().isEmpty()
                ? lang.plain(sender, "command.all-worlds") : String.join(", ", current.enabledWorlds());
        core.compat().platform().sendMessage(sender, status().commandLine(worlds));
        if (worldTrees != null) core.compat().platform().sendMessage(sender, String.format(Locale.ROOT, "CustomTrees world-trees: enabled=%s, height=%d, radius=%d, grown=%d, partial=%d, growing-now=%s",
                worldTreeSettings.enabled(), worldTreeSettings.treeHeight(), worldTreeSettings.treeRadius(), worldTrees.grownTotal(), worldTrees.partialTotal(), worldTrees.growing()));
        if (shaking != null && shakingOptions != null) core.compat().platform().sendMessage(sender, shaking.status(shakingOptions));
        if (herbarium != null) core.compat().platform().sendMessage(sender, herbarium.status());
        if (planting != null) core.compat().platform().sendMessage(sender, planting.status());
        if (timber != null) core.compat().platform().sendMessage(sender, timber.status());
        if (regrowth != null) core.compat().platform().sendMessage(sender, "CustomTrees " + regrowth.status());
        if (fruitTrees != null) core.compat().platform().sendMessage(sender, "CustomTrees " + fruitTrees.status());
        if (gardens != null) core.compat().platform().sendMessage(sender, "CustomTrees " + gardens.status());
        if (sender instanceof Player player) {
            Component buttons = core.message().chat().settings(lang.ref("action.settings"), SETTINGS_SECTION)
                    .append(Component.text(" ")).append(core.message().chat().menu(lang.ref("action.tutorial"), TUTORIAL_MENU));
            if (web != null && web.available()) {
                buttons = buttons.append(Component.text(" ")).append(guideButton(player, "admin"));
            }
            core.compat().platform().sendMessage(player, buttons);
        }
    }

    /** Web guide in the overlay or as chat link; without the web platform the in-game tutorial. Server thread. */
    private void openGuide(Player player, String page) {
        Runnable tutorial = () -> {
            if (!core.gui().openMenu(player, TUTORIAL_MENU)) lang.send(player, "command.guide-unavailable");
        };
        TreesWeb current = web;
        if (current == null) {
            tutorial.run();
            return;
        }
        current.open(player, page, tutorial);
    }

    private Component guideButton(Player player, String page) {
        return lang.component(player, "action.web-guide")
                .clickEvent(ClickEvent.runCommand("/customtrees guide" + (page.isEmpty() ? "" : " " + page)));
    }
}
