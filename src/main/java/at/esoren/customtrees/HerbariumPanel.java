package at.esoren.customtrees;

import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Collection screen. Data is read anew on every render; selection belongs to the viewer. */
final class HerbariumPanel {
    static final String NAME = "herbarium";

    record Row(Herbarium.Plant plant, List<Herbarium.Stage> found) {}
    record Overview(String name, int found, int total, Herbarium.Rank rank, List<Row> rows,
                    boolean planting, String selected) {}

    interface Actions {
        Result check(Player player);
        void suggest(Player player, String plant);
        void top(Player player);
        void planting(Player player);
    }

    private final HerbariumFeature feature;
    private final PlantingHelper planting;
    private final Actions actions;
    private final Map<UUID, String> selected = new HashMap<>();

    HerbariumPanel(HerbariumFeature feature, PlantingHelper planting, Actions actions) {
        this.feature = feature;
        this.planting = planting;
        this.actions = actions;
    }

    Panel render(PanelContext ctx, UUID target, String name) {
        return panel(ctx, overview(feature.data(), target, name, planting.usable(ctx.player()),
                selected.get(ctx.player().getUniqueId())), actions,
                (player, key) -> selected.put(player.getUniqueId(), key));
    }

    void forget(UUID player) { selected.remove(player); }

    static Overview overview(Herbarium data, UUID target, String name, boolean planting, String selected) {
        List<Row> rows = java.util.Arrays.stream(Herbarium.Plant.available())
                .map(plant -> new Row(plant, plant.stages().stream().filter(stage -> data.has(target, plant, stage)).toList()))
                .toList();
        int found = data.found(target);
        return new Overview(name, found, Herbarium.TOTAL, Herbarium.Rank.of(found), rows, planting, selected);
    }

    static Panel panel(PanelContext ctx, Overview o, Actions actions, BiConsumer<Player, String> selection) {
        Ui.Tabs tabs = Ui.tabs("plants");
        for (boolean fungus : new boolean[]{false, true}) {
            String group = fungus ? "fungi" : "trees";
            List<Row> rows = o.rows().stream().filter(row -> row.plant().fungus() == fungus).toList();
            if (rows.isEmpty()) continue;
            Row chosen = rows.stream().filter(row -> row.plant().id().equals(o.selected())).findFirst().orElse(rows.getFirst());
            if (chosen.plant().id().equals(o.selected())) tabs.select(group);
            Ui.ListView list = Ui.list("list-" + group).items(rows, row -> Ui.listItem(row.plant().id(),
                            ctx.text("herbarium.plant." + row.plant().id())).icon(Ui.item(row.plant().icon))
                    .right(ctx.text("herbarium.plant-progress", "count", row.found().size(), "total", row.plant().stages().size())))
                    .selected(chosen.plant().id()).grow(2).onSelect((a, key) -> {
                        Result check = actions.check(a.player());
                        if (!check.success()) return check;
                        if (rows.stream().noneMatch(row -> row.plant().id().equals(key)))
                            return Result.error(ctx.text("herbarium.next-unknown", "plant", key));
                        selection.accept(a.player(), key);
                        a.refresh();
                        return Result.ok();
                    });
            tabs.tab(group, ctx.text("panel." + group), Ui.row().gap(8).children(list, detail(ctx, chosen, actions)));
        }
        Herbarium.Rank next = o.rank().next();
        Panel panel = Panel.page(ctx.text("herbarium.title", "player", o.name()))
                .subtitle(ctx.text("herbarium.menu-description")).icon(Ui.item(Material.BOOK)).size(Panel.Size.LARGE)
                .children(Ui.badge(ctx.text("herbarium.rank." + o.rank().id())).id("rank").color("accent"),
                        Ui.progress(o.found(), o.total()).id("collection-progress")
                                .label(ctx.text("herbarium.summary-progress", "count", o.found(), "total", o.total())),
                        Ui.text(next == null ? ctx.text("herbarium.summary-max") : ctx.text("herbarium.summary-next",
                                "rank", ctx.text("herbarium.rank." + next.id()), "needed", next.needed())).id("next-rank"), tabs)
                .footer(Ui.button("top").label(ctx.text("herbarium.top-button")).icon(Ui.item(Material.GOLDEN_HOE)).ghost()
                        .onClick(a -> {
                            Result check = actions.check(a.player());
                            if (check.success()) a.closeAndThen(actions::top);
                            return check;
                        }));
        if (o.planting()) panel.footer(Ui.button("planting").label(ctx.text("herbarium.planting-button"))
                .icon(Ui.item(Material.OAK_SAPLING)).onClick(a -> {
                    Result check = actions.check(a.player());
                    if (check.success()) a.closeAndThen(actions::planting);
                    return check;
                }));
        return panel;
    }

    private static Ui.Box detail(PanelContext ctx, Row row, Actions actions) {
        String id = row.plant().id();
        Ui.Box detail = Ui.col().gap(6).grow(3).id("detail-" + id)
                .children(Ui.heading(ctx.text("herbarium.plant." + id)).level(2),
                        Ui.progress(row.found().size(), row.plant().stages().size()).id("progress-" + id)
                                .label(ctx.text("herbarium.plant-progress", "count", row.found().size(), "total", row.plant().stages().size())));
        for (Herbarium.Stage stage : row.plant().stages()) {
            detail.child(Ui.text(ctx.text(row.found().contains(stage) ? "herbarium.found" : "herbarium.missing",
                    "stage", ctx.text("herbarium.stage." + stage.id))).id("stage-" + id + "-" + stage.id));
        }
        return detail.child(Ui.button("next-" + id).label(ctx.text("panel.next-task"))
                .tooltip(ctx.text("herbarium.next-click")).icon(Ui.item(Material.BONE_MEAL)).primary().onClick(a -> {
                    Result check = actions.check(a.player());
                    if (check.success()) a.closeAndThen(player -> actions.suggest(player, id));
                    return check;
                }));
    }
}
