package at.esoren.customtrees;

import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Arming a formation only changes the helper selection; planting keeps its existing protection checks. */
final class PlantingPanel {
    static final String NAME = "planting";

    record Overview(List<PlantingStencil.Pattern> patterns, PlantingStencil.Pattern active, boolean herbarium) {}

    interface Actions {
        Result arm(Player player, PlantingStencil.Pattern pattern);
        Result disarm(Player player);
        Result check(Player player);
        void herbarium(Player player);
    }

    private final PlantingHelper helper;
    private final HerbariumFeature herbarium;
    private final Actions actions;

    PlantingPanel(PlantingHelper helper, HerbariumFeature herbarium, Actions actions) {
        this.helper = helper;
        this.herbarium = herbarium;
        this.actions = actions;
    }

    Panel render(PanelContext ctx) {
        return panel(ctx, new Overview(helper.menuPatterns(), helper.activePattern(ctx.player()), herbarium.usable(ctx.player())), actions);
    }

    static Panel panel(PanelContext ctx, Overview o, Actions actions) {
        Ui.Tabs tabs = Ui.tabs("formations");
        for (boolean hedge : new boolean[]{false, true}) {
            String group = hedge ? "hedges" : "squares";
            List<PlantingStencil.Pattern> patterns = o.patterns().stream().filter(pattern -> pattern.hedge() == hedge).toList();
            if (patterns.isEmpty()) continue;
            Ui.Box choices = Ui.col().gap(8);
            choices.child(Ui.text(ctx.text(hedge ? "planting.hedge-how" : "planting.how")));
            for (var pattern : patterns) {
                String id = key(pattern);
                Ui.Box choice = Ui.row().gap(8).id("choice-" + id);
                choice.child(Ui.button("arm-" + id).label(ctx.text(pattern.key("size"), "size", pattern.size()))
                        .icon(Ui.item(hedge ? Material.OAK_LEAVES : PlantingHelper.icon(pattern.size()))).primary()
                        .onClick(a -> {
                            Result result = actions.arm(a.player(), pattern);
                            if (result.success()) a.refresh();
                            return result;
                        }));
                Ui.Box description = Ui.col().child(Ui.text(ctx.text("planting.size-count", "count",
                        hedge ? pattern.size() : pattern.size() * pattern.size())));
                if (!hedge) description.child(Ui.text(ctx.text("planting.size-grows", "result", ctx.text("planting.result." + id))));
                if (pattern.equals(o.active())) description.child(Ui.badge(ctx.text("panel.active")).id("active-" + id).color("accent"));
                choices.child(choice.child(description));
            }
            tabs.tab(group, ctx.text("panel." + group), choices);
        }
        if (o.active() != null && o.patterns().contains(o.active())) tabs.select(o.active().hedge() ? "hedges" : "squares");
        Panel panel = Panel.page(ctx.text("planting.title")).subtitle(ctx.text("planting.menu-description"))
                .icon(Ui.item(Material.DARK_OAK_SAPLING)).size(Panel.Size.MEDIUM)
                .children(Ui.text(o.active() == null ? ctx.text("planting.status-none")
                        : ctx.text("panel.selected", "pattern", ctx.text(o.active().key("size"), "size", o.active().size()))).id("selection"), tabs)
                .footer(Ui.button("off").label(ctx.text("planting.off")).icon(Ui.item(Material.BARRIER)).ghost().onClick(a -> {
                    Result result = actions.disarm(a.player());
                    if (result.success()) a.refresh();
                    return result;
                }));
        if (o.herbarium()) panel.footer(Ui.button("herbarium").label(ctx.text("herbarium.menu-title"))
                .icon(Ui.item(Material.BOOK)).ghost().onClick(a -> {
                    Result check = actions.check(a.player());
                    if (check.success()) a.closeAndThen(actions::herbarium);
                    return check;
                }));
        return panel;
    }

    static String key(PlantingStencil.Pattern pattern) {
        return pattern.hedge() ? "hedge" + pattern.size() : pattern.size() + "x" + pattern.size();
    }
}
