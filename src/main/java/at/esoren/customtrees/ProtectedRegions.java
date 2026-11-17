package at.esoren.customtrees;

import Vystorm.vystorm_core.region.Region;
import Vystorm.vystorm_core.region.RegionPriority;
import Vystorm.vystorm_core.region.RegionService;
import Vystorm.vystorm_core.region.RegionShape;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Regions from the Core region registry that automatic world changes must not
 * touch: claims, city plots, WorldForge structures, dungeons, arenas, markets
 * and spawn. Broad territories (faction influence) and default regions do not
 * block anything.
 */
final class ProtectedRegions {
    private ProtectedRegions() {
    }

    static boolean isProtected(Region region) {
        return region.priority() > RegionPriority.TERRITORY;
    }

    static List<RegionShape> within(RegionService regions, UUID world, int minX, int minZ, int maxX, int maxZ) {
        List<RegionShape> shapes = new ArrayList<>();
        for (Region region : regions.intersecting(world, minX, minZ, maxX, maxZ)) {
            if (isProtected(region)) {
                shapes.add(region.shape());
            }
        }
        return shapes;
    }

    static boolean contains(List<RegionShape> shapes, UUID world, int x, int y, int z) {
        for (RegionShape shape : shapes) {
            if (shape.contains(world, x, y, z)) {
                return true;
            }
        }
        return false;
    }
}
