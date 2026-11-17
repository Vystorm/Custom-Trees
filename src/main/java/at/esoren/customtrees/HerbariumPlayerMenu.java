package at.esoren.customtrees;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.compat.PlatformCompatInterface;
import Vystorm.vystorm_core.playermenu.PlayerMenu;
import Vystorm.vystorm_core.playermenu.PlayerMenuInterface;
import Vystorm.vystorm_core.playermenu.PlayerMenuEntry;
import java.util.List;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;

/**
 * "Herbarium of {player}" in Core's player menu (Core 0.43.0+, sneak-right-click another player).
 */
final class HerbariumPlayerMenu {
    private HerbariumPlayerMenu() {}

    static void register(PlayerMenuInterface menu, PlatformCompatInterface platform, Plugin plugin, HerbariumFeature herbarium, Lang lang) {
        menu.register(plugin, PlayerMenuEntry.builder("herbarium")
                .priority(240)
                .visibleWhen((viewer, target) -> herbarium.enabled() && viewer.hasPermission("customtrees.herbarium"))
                .icon((viewer, target) -> {
                    int found = herbarium.data().found(target.getUniqueId());
                    return HerbariumFeature.item(platform, Material.OAK_SAPLING,
                            lang.component(viewer, "herbarium.player-menu", "player", target.getName()),
                            List.of(lang.component(viewer, "herbarium.player-menu-lore", "count", found,
                                    "total", Herbarium.TOTAL, "rank", herbarium.rankName(viewer, Herbarium.Rank.of(found)))
                                    .decoration(TextDecoration.ITALIC, false)),
                            false);
                })
                .onClick((viewer, target, click) -> {
                    herbarium.open(viewer, target.getUniqueId(), target.getName());
                    return PlayerMenu.Result.KEEP;
                })
                .build());
    }
}
