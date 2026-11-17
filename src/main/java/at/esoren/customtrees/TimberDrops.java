package at.esoren.customtrees;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;

/** Tool wear and drop merging of a felled tree. */
final class TimberDrops {
    private TimberDrops() {}

    /** Durability points a fell costs before Unbreaking: {@code logs × factor}, rounded up (0 with factor 0). */
    static int damage(int logs, double factor) {
        if (logs <= 0 || factor <= 0) return 0;
        return (int) Math.min(Integer.MAX_VALUE, Math.ceil(logs * factor - 1e-9));
    }

    /** Average damage Unbreaking lets through: each point is ignored with chance level/(level+1), rounded up. */
    static int expectedDamage(int damage, int unbreaking) {
        if (damage <= 0) return 0;
        return (int) Math.ceil(damage / (double) (Math.max(0, unbreaking) + 1) - 1e-9);
    }

    /**
     * Whether the tool survives a fell: {@code remaining} durability points left (max minus current damage) must stay
     * above the expected damage. Unbreakable tools and items without durability always survive.
     */
    static boolean toolSurvives(int maxDurability, int currentDamage, int damage, int unbreaking, boolean unbreakable) {
        if (unbreakable || maxDurability <= 0) return true;
        int remaining = maxDurability - currentDamage;
        return remaining > expectedDamage(damage, unbreaking);
    }

    /**
     * Merges drops into as few full stacks as possible. {@code similar} decides whether two items stack,
     * {@code amount}/{@code maxStack} read them and {@code withAmount} copies an item with another amount.
     */
    static <T> List<T> merge(List<T> drops, BiPredicate<T, T> similar, ToIntFunction<T> amount, ToIntFunction<T> maxStack,
            WithAmount<T> withAmount) {
        List<T> kinds = new ArrayList<>();
        List<Integer> totals = new ArrayList<>();
        outer:
        for (T drop : drops) {
            int count = amount.applyAsInt(drop);
            if (count <= 0) continue;
            for (int i = 0; i < kinds.size(); i++) {
                if (similar.test(kinds.get(i), drop)) { totals.set(i, totals.get(i) + count); continue outer; }
            }
            kinds.add(drop);
            totals.add(count);
        }
        List<T> stacks = new ArrayList<>();
        for (int i = 0; i < kinds.size(); i++) {
            T kind = kinds.get(i);
            int max = Math.max(1, maxStack.applyAsInt(kind)), left = totals.get(i);
            while (left > 0) {
                int take = Math.min(max, left);
                stacks.add(withAmount.copy(kind, take));
                left -= take;
            }
        }
        return stacks;
    }

    interface WithAmount<T> { T copy(T item, int amount); }
}
