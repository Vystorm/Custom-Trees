package at.esoren.customtrees;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.PanelOpenResult;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Panels;
import Vystorm.vystorm_core.panel.Result;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Optional Core integration, loaded only behind the capability guard in the plugin. */
final class TreesPanels {
    record Target(UUID id, String name) {}

    private final CustomTreesPlugin plugin;
    private final Panels panels;
    private final HerbariumPanel herbariumPanel;
    private final PlantingPanel plantingPanel;
    private final Map<UUID, Target> targets = new HashMap<>();

    TreesPanels(CustomTreesPlugin plugin) {
        this.plugin = plugin;
        panels = plugin.core().gui().panels().of(plugin);
        herbariumPanel = new HerbariumPanel(plugin.herbarium(), plugin.planting(), herbariumActions(plugin));
        plantingPanel = new PlantingPanel(plugin.planting(), plugin.herbarium(), plantingActions(plugin));
    }

    void register() {
        Lang lang = plugin.lang();
        // Keep the existing /vmenu registrations: their openers reset the target to self and handle open failures.
        panels.register(HerbariumPanel.NAME, PanelSpec.builder(lang.ref("herbarium.menu-title"))
                .access(p -> plugin.isEnabled() && plugin.herbarium().usable(p))
                .render(ctx -> {
                    Target target = target(ctx.player());
                    return herbariumPanel.render(ctx, target.id(), target.name());
                })
                .fallback(p -> {
                    Target target = target(p);
                    plugin.herbarium().openChest(p, target.id(), target.name());
                }).build());
        panels.register(PlantingPanel.NAME, PanelSpec.builder(lang.ref("planting.menu-title"))
                .access(p -> plugin.isEnabled() && plugin.planting().usable(p))
                .render(plantingPanel::render).fallback(plugin.planting()::openChest).build());
    }

    private Target target(Player player) {
        return targets.getOrDefault(player.getUniqueId(), new Target(player.getUniqueId(), player.getName()));
    }

    boolean openHerbarium(Player viewer, UUID target, String name) {
        targets.put(viewer.getUniqueId(), new Target(target, name == null ? viewer.getName() : name));
        return panels.open(viewer, HerbariumPanel.NAME) != PanelOpenResult.NONE;
    }

    boolean openPlanting(Player player) { return panels.open(player, PlantingPanel.NAME) != PanelOpenResult.NONE; }

    void forget(UUID player) {
        targets.remove(player);
        herbariumPanel.forget(player);
    }

    void close() {
        plugin.core().gui().panels().release(plugin);
        targets.clear();
    }

    private static Result herbariumCheck(CustomTreesPlugin plugin, Player player) {
        if (!plugin.isEnabled() || !player.isOnline() || !plugin.herbarium().enabled())
            return Result.error(plugin.lang().plain(player, "feature-off"));
        if (!player.hasPermission("customtrees.herbarium"))
            return Result.error(plugin.lang().plain(player, "command.no-permission"));
        return Result.ok();
    }

    private static Result plantingCheck(CustomTreesPlugin plugin, Player player) {
        if (!plugin.isEnabled() || !player.isOnline() || !plugin.planting().options().enabled())
            return Result.error(plugin.lang().plain(player, "feature-off"));
        if (!player.hasPermission("customtrees.plant"))
            return Result.error(plugin.lang().plain(player, "command.no-permission"));
        return Result.ok();
    }

    static HerbariumPanel.Actions herbariumActions(CustomTreesPlugin plugin) {
        return new HerbariumPanel.Actions() {
            public Result check(Player player) { return herbariumCheck(plugin, player); }
            public void suggest(Player player, String plant) {
                if (check(player).success()) plugin.herbarium().suggest(player, plant);
            }
            public void top(Player player) {
                if (check(player).success()) plugin.herbarium().sendTop(player);
            }
            public void planting(Player player) {
                if (check(player).success() && plantingCheck(plugin, player).success()) plugin.planting().openMenu(player);
            }
        };
    }

    static PlantingPanel.Actions plantingActions(CustomTreesPlugin plugin) {
        return new PlantingPanel.Actions() {
            public Result check(Player player) { return plantingCheck(plugin, player); }
            public Result arm(Player player, PlantingStencil.Pattern pattern) {
                Result check = check(player);
                if (!check.success()) return check;
                if (!plugin.planting().menuPatterns().contains(pattern) || !pattern.allowed(plugin.planting().options()))
                    return Result.error(plugin.lang().plain(player, "planting.disabled"));
                plugin.planting().arm(player, pattern);
                return Result.ok();
            }
            public Result disarm(Player player) {
                Result check = check(player);
                if (!check.success()) return check;
                plugin.planting().disarm(player);
                return Result.ok();
            }
            public void herbarium(Player player) {
                if (check(player).success() && herbariumCheck(plugin, player).success())
                    plugin.herbarium().open(player, player.getUniqueId(), player.getName());
            }
        };
    }
}
