package at.esoren.customtrees;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bukkit.Material;

/**
 * Replanting after a fell ({@code timber.replant}): which sapling belongs to a log and where it goes. Only small trees
 * are replanted, on the spots of their lowest logs: one sapling for a single trunk, four for a 2x2 trunk.
 */
final class TimberReplant {
    private TimberReplant() {}

    /** The sapling, propagule or fungus that grows this log or stem, or null (mushroom stems, unknown wood). */
    static Material sapling(Material log) {
        if (log == null) return null;
        String name = log.name();
        if (name.startsWith("STRIPPED_")) name = name.substring("STRIPPED_".length());
        String base;
        if (name.endsWith("_LOG")) base = name.substring(0, name.length() - 4);
        else if (name.endsWith("_WOOD")) base = name.substring(0, name.length() - 5);
        else if (name.endsWith("_STEM")) base = name.substring(0, name.length() - 5);
        else if (name.endsWith("_HYPHAE")) base = name.substring(0, name.length() - 7);
        else return null;
        return switch (base) {
            case "MANGROVE" -> Material.MANGROVE_PROPAGULE;
            case "CRIMSON" -> Material.CRIMSON_FUNGUS;
            case "WARPED" -> Material.WARPED_FUNGUS;
            default -> {
                Material sapling = Material.getMaterial(base + "_SAPLING");
                yield sapling;
            }
        };
    }

    /**
     * The packed positions ({@link TimberScanner#pack}) where saplings go: the trunk logs of the lowest layer when
     * they are a single block or a 2x2 square; otherwise none (shrubs, hedges, odd trunks).
     */
    static List<Long> spots(Collection<Long> logs) {
        if (logs.isEmpty()) return List.of();
        int minY = Integer.MAX_VALUE;
        for (long pos : logs) minY = Math.min(minY, TimberScanner.y(pos));
        List<Long> bottom = new ArrayList<>();
        for (long pos : logs) if (TimberScanner.y(pos) == minY && !bottom.contains(pos)) bottom.add(pos);
        if (bottom.size() == 1) return bottom;
        if (bottom.size() != 4) return List.of();
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long pos : bottom) {
            minX = Math.min(minX, TimberScanner.x(pos)); maxX = Math.max(maxX, TimberScanner.x(pos));
            minZ = Math.min(minZ, TimberScanner.z(pos)); maxZ = Math.max(maxZ, TimberScanner.z(pos));
        }
        return maxX - minX == 1 && maxZ - minZ == 1 ? bottom : List.of();
    }
}
