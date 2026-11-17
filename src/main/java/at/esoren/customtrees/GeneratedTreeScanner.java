package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructureAccess;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructurePos;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeSpecies;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;

/**
 * Pure search and removal logic of the new-chunk pass. Base search only reads
 * the chunk itself (it runs on a chunk snapshot off the main thread); removal
 * only touches positions the given {@link StructureAccess} contains.
 */
final class GeneratedTreeScanner {
    static final Set<Material> SOILS = PaperCompat.materials(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT,
            Material.PODZOL, Material.MYCELIUM, Material.MOSS_BLOCK, PaperCompat.material("PALE_MOSS_BLOCK"),
            Material.MUD, Material.MUDDY_MANGROVE_ROOTS, Material.MANGROVE_ROOTS,
            Material.SAND, Material.RED_SAND);
    /** Upper bound of log blocks visited while measuring one generated tree. */
    static final int MAX_TRUNK_NODES = 8192;
    /** Largest horizontal distance from the base that belongs to one generated tree. */
    static final int MAX_REMOVAL_RADIUS = 18;
    static final int MIN_REMOVAL_RADIUS = 6;
    /** Extra horizontal margin around the measured logs for the crown. */
    static final int CROWN_MARGIN = 5;
    /** Highest log above the base that still counts as the same tree. */
    static final int MAX_TREE_HEIGHT = 112;
    /** Leaves above the highest log that are still removed with the tree. */
    static final int CROWN_CLEARANCE = 12;

    private GeneratedTreeScanner() {
    }

    /** Read-only view on one chunk, in world coordinates. */
    interface ChunkReader {
        Material type(int x, int y, int z);

        /** Highest non-air block of the column. */
        int highestY(int x, int z);
    }

    /** Positions the pass must never change. */
    @FunctionalInterface
    interface PositionFilter {
        boolean test(int x, int y, int z);
    }

    record TreeBase(StructurePos position, TreeSpecies species) {
    }

    record TreeRemoval(TreeBase base, int topY, int radius) {
    }

    static List<TreeBase> findBases(ChunkReader reader, long seed, int startX, int startZ,
            int minHeight, int maxHeight, int scanDepth, double replacementChance) {
        List<TreeBase> bases = new ArrayList<>();
        for (int x = startX; x < startX + 16; x++) {
            for (int z = startZ; z < startZ + 16; z++) {
                int top = Math.min(maxHeight - 1, reader.highestY(x, z));
                int bottom = Math.max(minHeight + 1, top - scanDepth);
                for (int y = top; y >= bottom; y--) {
                    TreeSpecies species = TreeSpecies.fromLog(reader.type(x, y, z));
                    if (species != null && SOILS.contains(reader.type(x, y - 1, z))) {
                        TreeBase candidate = new TreeBase(new StructurePos(x, y, z), species);
                        if (!isSameTrunkAlreadyFound(bases, candidate)
                                && replacementRoll(seed, x, y, z) < replacementChance) {
                            bases.add(candidate);
                        }
                        break;
                    }
                }
            }
        }
        return bases;
    }

    static double replacementRoll(long seed, int x, int y, int z) {
        long mixed = seed;
        mixed ^= (long) x * 0x9E3779B97F4A7C15L;
        mixed ^= (long) z * 0xC2B2AE3D27D4EB4FL;
        mixed ^= (long) y * 0x165667B19E3779F9L;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return (mixed >>> 11) * 0x1.0p-53;
    }

    private static boolean isSameTrunkAlreadyFound(List<TreeBase> bases, TreeBase candidate) {
        for (TreeBase existing : bases) {
            if (existing.species() == candidate.species()
                    && Math.abs(existing.position().x() - candidate.position().x()) <= 2
                    && Math.abs(existing.position().z() - candidate.position().z()) <= 2
                    && Math.abs(existing.position().y() - candidate.position().y()) <= 1) {
                return true;
            }
        }
        return false;
    }

    static TreeRemoval measure(StructureAccess access, TreeBase base, PositionFilter keep) {
        ArrayDeque<StructurePos> pending = new ArrayDeque<>();
        Set<StructurePos> visited = new HashSet<>();
        StructurePos origin = base.position();
        pending.add(origin);
        int top = origin.y();
        int horizontalReach = 0;
        while (!pending.isEmpty() && visited.size() < MAX_TRUNK_NODES) {
            StructurePos position = pending.removeFirst();
            if (!visited.add(position)
                    || Math.abs(position.x() - origin.x()) > MAX_REMOVAL_RADIUS
                    || Math.abs(position.z() - origin.z()) > MAX_REMOVAL_RADIUS
                    || position.y() < origin.y() - 3
                    || position.y() > origin.y() + MAX_TREE_HEIGHT
                    || !access.contains(position.x(), position.y(), position.z())
                    || keep.test(position.x(), position.y(), position.z())
                    || !TreeSpecies.isLog(access.getType(position.x(), position.y(), position.z()))) {
                continue;
            }
            top = Math.max(top, position.y());
            horizontalReach = Math.max(horizontalReach,
                    Math.max(Math.abs(position.x() - origin.x()), Math.abs(position.z() - origin.z())));
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx != 0 || dy != 0 || dz != 0) {
                            pending.add(position.add(dx, dy, dz));
                        }
                    }
                }
            }
        }
        int heightRadius = (top - origin.y()) / 3 + 4;
        int radius = Math.max(MIN_REMOVAL_RADIUS, Math.min(MAX_REMOVAL_RADIUS,
                Math.max(horizontalReach + CROWN_MARGIN, heightRadius)));
        return new TreeRemoval(base, top, radius);
    }

    /** Clears logs, leaves, vines and cocoa of the measured tree; returns the number of cleared blocks. */
    static int remove(StructureAccess access, TreeRemoval removal, PositionFilter keep) {
        StructurePos base = removal.base().position();
        int removed = 0;
        for (int x = base.x() - removal.radius(); x <= base.x() + removal.radius(); x++) {
            for (int z = base.z() - removal.radius(); z <= base.z() + removal.radius(); z++) {
                for (int y = base.y(); y <= removal.topY() + CROWN_CLEARANCE; y++) {
                    if (!access.contains(x, y, z)) {
                        continue;
                    }
                    Material material = access.getType(x, y, z);
                    if ((TreeSpecies.isLog(material) || TreeSpecies.isLeaves(material)
                            || material == Material.VINE || material == Material.COCOA
                            || material == PaperCompat.PALE_HANGING_MOSS || material == Material.MANGROVE_PROPAGULE)
                            && !keep.test(x, y, z)) {
                        access.setType(x, y, z, Material.AIR);
                        removed++;
                    }
                }
            }
        }
        return removed;
    }
}
