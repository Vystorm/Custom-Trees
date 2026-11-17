package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructureAccess;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructurePos;
import java.util.Random;
import org.bukkit.Material;

/** Bounded surface patches; the caller supplies protection-aware world access. */
final class SpruceGround {
    @FunctionalInterface
    interface Temperature { double at(int x, int y, int z); }

    private SpruceGround() {}

    static boolean isSoil(Material material) {
        return material == Material.GRASS_BLOCK || material == Material.DIRT
                || material == Material.PODZOL || material == Material.COARSE_DIRT;
    }

    static void decorate(StructureAccess access, StructurePos base, int radius, long seed,
            TreeSettings settings, Temperature temperature) {
        if (!settings.spruceGround() && !settings.spruceSnow()) return;
        // A separate RNG keeps existing tree shapes and catalyst decorations unchanged.
        Random random = new Random(seed ^ 0x535052554345L);
        radius = Math.max(1, Math.min(8, radius));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > radius || (distance > radius - 2 && random.nextDouble() < 0.45)) continue;
                int x = base.x() + dx, z = base.z() + dz;
                // Follow nearby slopes, never descend into caves or carve existing blocks.
                for (int y = base.y() + 2; y >= base.y() - 3; y--) {
                    if (!access.contains(x, y, z) || !access.contains(x, y + 1, z)) continue;
                    Material soil = access.getType(x, y, z);
                    Material above = access.getType(x, y + 1, z);
                    boolean air = above == Material.AIR || above == Material.CAVE_AIR || above == Material.VOID_AIR;
                    if (!isSoil(soil) || !(air || above == Material.SNOW
                            || above == Material.SHORT_GRASS || above == Material.FERN)) continue;
                    if (settings.spruceGround()) {
                        Material replacement = random.nextDouble() < 0.8 ? Material.PODZOL : Material.COARSE_DIRT;
                        if (access instanceof BufferedTreeAccess buffered) buffered.planGround(x, y, z, replacement);
                        else access.setType(x, y, z, replacement);
                    }
                    if (settings.spruceSnow() && air
                            && (!settings.spruceSnowColdOnly() || temperature.at(x, y + 1, z) < 0.15)) {
                        access.setType(x, y + 1, z, Material.SNOW);
                    }
                    break;
                }
            }
        }
    }
}
