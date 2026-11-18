package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderNamespace;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderValue;
import java.util.List;
import java.util.function.UnaryOperator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The values of CustomTrees in Core's placeholder registry, namespace {@code trees}. The herbarium and the counters
 * live in plain maps, so every value except the fixed total is answered on the server thread only. A value of a
 * switched-off feature is empty.
 *
 * <pre>
 * %vystorm_trees_herbarium_found%        {trees:herbarium_found}        forms the player found
 * %vystorm_trees_herbarium_top_1_name%   {trees:herbarium_top:1:name}   collector on place 1 (_found: the number)
 * herbarium_total, herbarium_rank, herbarium_rank_id, herbarium_next_needed, herbarium_keepers, breed_cooldown,
 * trees_grown, giant_grown, colossal_grown, shrubs_grown, hedges_grown
 * </pre>
 */
final class TreesPlaceholders {
    static final String NAMESPACE = "trees";

    /** What the values read, at call time. */
    interface Source {
        boolean on(Feature feature);
        /** The herbarium data, or null before it is loaded. */
        Herbarium herbarium();
        String rankName(Player viewer, Herbarium.Rank rank);
        /** Seconds until the player may breed again, 0 when ready. */
        long breedCooldown(Player player);
        /** Grown counters since the start: regular, giant, colossal, shrubs, hedges; null before the start. */
        long[] grown();
    }

    private TreesPlaceholders() {}

    static PlaceholderNamespace register(PlaceholderInterface registry, Plugin owner, Source source, UnaryOperator<String> describe) {
        PlaceholderNamespace ns = registry.register(owner, NAMESPACE);
        ns.value("herbarium_found", describe.apply("placeholder.herbarium_found"), viewer -> {
            Herbarium data = herbarium(source);
            return data == null ? "" : data.found(viewer.getUniqueId());
        });
        ns.add(PlaceholderValue.global("herbarium_total", () -> source.on(Feature.HERBARIUM) ? Herbarium.TOTAL : "").asyncSafe()
                .description(describe.apply("placeholder.herbarium_total")));
        ns.player("herbarium_rank", describe.apply("placeholder.herbarium_rank"), player -> {
            Herbarium data = herbarium(source);
            return data == null ? "" : source.rankName(player, Herbarium.Rank.of(data.found(player.getUniqueId())));
        });
        ns.value("herbarium_rank_id", describe.apply("placeholder.herbarium_rank_id"), viewer -> {
            Herbarium data = herbarium(source);
            return data == null ? "" : Herbarium.Rank.of(data.found(viewer.getUniqueId())).id();
        });
        ns.value("herbarium_next_needed", describe.apply("placeholder.herbarium_next_needed"), viewer -> {
            Herbarium data = herbarium(source);
            if (data == null) return "";
            int found = data.found(viewer.getUniqueId());
            Herbarium.Rank next = Herbarium.Rank.of(found).next();
            return next == null ? 0 : Math.max(0, next.needed() - found);
        });
        ns.add(PlaceholderValue.of("herbarium_top", context -> {
            Herbarium data = herbarium(source);
            if (data == null) return "";
            String part = context.argument(1, "name");
            if (!part.equalsIgnoreCase("name") && !part.equalsIgnoreCase("found")) return null;
            int place = context.intArgument(0, 1);
            if (place < 1 || place > 100) return "";
            List<Herbarium.Standing> top = data.top(place);
            if (top.size() < place) return "";
            Herbarium.Standing standing = top.get(place - 1);
            return part.equalsIgnoreCase("name") ? standing.name() : standing.found();
        }).arguments("<place>_<name|found>").cacheTicks(20).description(describe.apply("placeholder.herbarium_top")));
        ns.global("herbarium_keepers", describe.apply("placeholder.herbarium_keepers"), () -> {
            Herbarium data = herbarium(source);
            return data == null ? "" : data.keepers();
        });
        ns.player("breed_cooldown", describe.apply("placeholder.breed_cooldown"), player ->
                source.on(Feature.BREEDING) ? source.breedCooldown(player) : "");
        String[] counters = {"trees_grown", "giant_grown", "colossal_grown", "shrubs_grown", "hedges_grown"};
        Feature[] features = {Feature.CUSTOM_GROWTH, Feature.GIANT, Feature.COLOSSAL, Feature.SHRUBS_HEDGES, Feature.SHRUBS_HEDGES};
        for (int i = 0; i < counters.length; i++) {
            int index = i;
            ns.global(counters[i], describe.apply("placeholder." + counters[i]), () -> {
                long[] grown = source.grown();
                return grown == null || !source.on(features[index]) ? "" : grown[index];
            });
        }
        return ns;
    }

    private static Herbarium herbarium(Source source) {
        return source.on(Feature.HERBARIUM) ? source.herbarium() : null;
    }

    /** Seconds left of a breeding cooldown that ends at {@code readyMillis}. */
    static long secondsLeft(long readyMillis, long now) {
        return readyMillis <= now ? 0 : (readyMillis - now + 999) / 1000;
    }

}
