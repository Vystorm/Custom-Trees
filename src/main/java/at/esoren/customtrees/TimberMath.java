package at.esoren.customtrees;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Geometry of a falling tree: the fall direction from the player's view, the rotation of the tree around its stump,
 * where an obstacle stops the fall, and the reduced block set ("proxy") for trees with more blocks than displays.
 */
final class TimberMath {
    private TimberMath() {}

    /** Horizontal unit vector (x, z) the player looks at; Minecraft yaw 0 = south (+z), 90 = west (-x). */
    static double[] direction(float yaw) {
        double radians = Math.toRadians(yaw);
        return new double[] {-Math.sin(radians), Math.cos(radians)};
    }

    /**
     * Fall direction: away from the player, i.e. from the player towards the trunk ("forward"). When the player
     * stands (almost) inside the trunk column, the view direction decides.
     */
    static double[] fallDirection(double playerX, double playerZ, float yaw, double pivotX, double pivotZ) {
        double dx = pivotX - playerX, dz = pivotZ - playerZ, length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.75) return direction(yaw);
        double[] view = direction(yaw);
        // Mostly away from the player, bent a little towards where they look.
        double x = dx / length * 0.75 + view[0] * 0.25, z = dz / length * 0.75 + view[1] * 0.25;
        double norm = Math.sqrt(x * x + z * z);
        return norm < 1e-6 ? view : new double[] {x / norm, z / norm};
    }

    /** Rotation axis (horizontal, through the pivot) that tips "up" towards the direction: up × direction. */
    static double[] axis(double[] direction) {
        return new double[] {direction[1], 0, -direction[0]};
    }

    /** Rodrigues rotation of a vector around a unit axis by {@code angle} radians. */
    static double[] rotate(double[] v, double[] k, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        double dot = k[0] * v[0] + k[1] * v[1] + k[2] * v[2];
        double cx = k[1] * v[2] - k[2] * v[1], cy = k[2] * v[0] - k[0] * v[2], cz = k[0] * v[1] - k[1] * v[0];
        return new double[] {
                v[0] * cos + cx * sin + k[0] * dot * (1 - cos),
                v[1] * cos + cy * sin + k[1] * dot * (1 - cos),
                v[2] * cos + cz * sin + k[2] * dot * (1 - cos)};
    }

    /** Quaternion (x, y, z, w) for a rotation around a unit axis. */
    static float[] quaternion(double[] k, double angle) {
        double half = angle / 2, sin = Math.sin(half);
        return new float[] {(float) (k[0] * sin), (float) (k[1] * sin), (float) (k[2] * sin), (float) Math.cos(half)};
    }

    /** Blocked world positions; the tree's own blocks must not count. */
    interface Blocked {
        boolean at(int x, int y, int z);
    }

    /**
     * The largest fall angle (degrees, 0..maxDegrees in {@code step} steps) before a sample point of the tree would
     * move into a blocked block. Points are block centres relative to the pivot; points close to the pivot are ignored
     * (the stump touches the ground anyway).
     */
    static double stopAngle(List<double[]> points, double[] pivot, double[] axis, double maxDegrees, double step, Blocked blocked) {
        double free = 0;
        for (double degrees = step; degrees <= maxDegrees + 1e-9; degrees += step) {
            double radians = Math.toRadians(degrees);
            for (double[] point : points) {
                if (point[0] * point[0] + point[1] * point[1] + point[2] * point[2] < 2.25) continue;
                double[] moved = rotate(point, axis, radians);
                int x = (int) Math.floor(pivot[0] + moved[0]), y = (int) Math.floor(pivot[1] + moved[1]),
                        z = (int) Math.floor(pivot[2] + moved[2]);
                if (blocked.at(x, y, z)) return free;
            }
            free = degrees;
        }
        return free;
    }

    /** Fall time in ticks: a full 90° fall takes {@code fullTicks}; shorter arcs are quicker (free-fall like). */
    static int fallTicks(double degrees, int fullTicks) {
        if (degrees <= 0) return 1;
        return Math.max(4, (int) Math.round(fullTicks * Math.sqrt(Math.min(degrees, 90) / 90.0)));
    }

    /** Angle after {@code tick} of {@code total} ticks: accelerating like a falling tree ((t/T)²). */
    static double angleAt(int tick, int total, double finalDegrees) {
        if (total <= 0) return finalDegrees;
        double t = Math.clamp(tick / (double) total, 0, 1);
        return finalDegrees * t * t;
    }

    /** One display of a falling tree: a cube of {@code size} blocks at the block offset, showing block {@code source}. */
    record Voxel(int x, int y, int z, int size, int source) {}

    /**
     * Reduces a tree to at most {@code maxDisplays} cubes. Each block is (x, y, z, priority) with priority 0 = log,
     * 1 = crown, 2 = attachment; a merged cube shows its most important block (logs win, so the trunk stays visible).
     * Size 1 keeps every block when the tree is small enough.
     */
    static List<Voxel> proxy(List<int[]> blocks, int maxDisplays) {
        if (blocks.isEmpty()) return List.of();
        for (int size = 1; size <= 16; size++) {
            Map<Long, Integer> cells = new LinkedHashMap<>();
            for (int i = 0; i < blocks.size(); i++) {
                int[] b = blocks.get(i);
                long key = TimberScanner.pack(Math.floorDiv(b[0], size), Math.floorDiv(b[1], size), Math.floorDiv(b[2], size));
                Integer current = cells.get(key);
                if (current == null || blocks.get(current)[3] > b[3]) cells.put(key, i);
            }
            if (cells.size() <= maxDisplays || size == 16) {
                List<Voxel> voxels = new ArrayList<>(cells.size());
                for (Map.Entry<Long, Integer> cell : cells.entrySet()) {
                    long key = cell.getKey();
                    voxels.add(new Voxel(TimberScanner.x(key) * size, TimberScanner.y(key) * size,
                            TimberScanner.z(key) * size, size, cell.getValue()));
                    if (voxels.size() >= maxDisplays) break;
                }
                return voxels;
            }
        }
        return List.of();
    }

    /** Evenly spread sample of at most {@code max} entries (keeps the first and spreads the rest). */
    static <T> List<T> sample(List<T> values, int max) {
        if (values.size() <= max) return values;
        List<T> sample = new ArrayList<>(max);
        double step = values.size() / (double) max;
        for (int i = 0; i < max; i++) sample.add(values.get((int) (i * step)));
        return sample;
    }

    /** Groups packed positions by their {@code cell}×{@code cell} column (drop spots of a straight-down fell). */
    static Map<Long, List<Long>> columns(List<Long> positions, int cell) {
        Map<Long, List<Long>> columns = new HashMap<>();
        for (long p : positions) {
            long key = TimberScanner.pack(Math.floorDiv(TimberScanner.x(p), cell), 0, Math.floorDiv(TimberScanner.z(p), cell));
            columns.computeIfAbsent(key, ignored -> new ArrayList<>()).add(p);
        }
        return columns;
    }
}
