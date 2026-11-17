package at.esoren.customtrees;

import Vystorm.vystorm_core.compat.ServerCompat;
import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.block.data.BlockData;

final class PaperCompat {
    static final Material PALE_HANGING_MOSS = material("PALE_HANGING_MOSS");

    private PaperCompat() {}

    static Material material(String name) { return ServerCompat.material(name, null); }

    static String key(org.bukkit.Keyed value) { return value.getKey().toString(); }

    static Set<Material> materials(Material... values) {
        Set<Material> result = EnumSet.noneOf(Material.class);
        for (Material value : values) if (value != null) result.add(value);
        return result;
    }

    static TreeType treeType(String name, TreeType fallback) {
        try { return TreeType.valueOf(name); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }

    interface Blocks {
        BlockData hangingMoss(Material material, boolean tip);
    }

    private static final class Holder {
        static final Blocks BLOCKS = selectBlocks(ServerCompat.hasClass("org.bukkit.block.data.type.HangingMoss"));
    }

    static Blocks blocks() { return Holder.BLOCKS; }

    static Blocks selectBlocks(boolean hangingMoss) {
        if (hangingMoss) {
            try {
                return (Blocks) Class.forName("at.esoren.customtrees.ModernBlocks").getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot load block adapter", failure);
            }
        }
        return (material, tip) -> material.createBlockData();
    }
}
