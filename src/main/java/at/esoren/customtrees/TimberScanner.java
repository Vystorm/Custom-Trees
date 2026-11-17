package at.esoren.customtrees;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Axis;
import org.bukkit.Material;

/**
 * Finds the whole tree behind a chopped log: its logs above the cut, the crown (leaves or caps) and attached blocks
 * (vines, cocoa, hanging moss, tree glowstone). Works on a read-only {@link View}, so it runs off the server thread on
 * chunk snapshots, and it is bounded by block count, radius and height (iterative breadth-first, no recursion).
 * <p>
 * Player builds are rejected: a log next to any non-natural block (planks, glass, torches, ...), a lying trunk, a
 * trunk that does not stand on natural ground, or a "tree" without enough natural leaves is not a tree. Leaves count
 * only when they decay naturally ({@code persistent=false}) and are not closer to another tree, or when CustomTrees
 * itself marked them while growing the tree.
 */
final class TimberScanner {
    private TimberScanner() {}

    /** One block as the scanner sees it; {@code marked} = CustomTrees grew or generated this block. */
    record Cell(Material type, Axis axis, boolean persistent, int distance, boolean marked) {
        static Cell of(Material type) { return new Cell(type, null, false, 7, false); }
    }

    /** Read-only block access; {@code null} = unknown (not loaded or outside the captured area). */
    interface View {
        Cell at(int x, int y, int z);
    }

    enum Verdict { TREE, NOT_A_LOG, NO_CROWN, FOREIGN_BLOCK, NOT_GROUNDED, LYING_TRUNK, TOO_LARGE, OUTSIDE_VIEW }

    /**
     * @param maxRadius  radius of this scan (the first scan is cheap and smaller)
     * @param fullRadius configured maximum ({@code detection.max-radius}). A tree that reaches past {@code maxRadius} but
     *                   stays within {@code fullRadius} asks for a wider view instead of counting as too large.
     */
    record Limits(int maxBlocks, int maxRadius, int maxHeight, int minLeaves, boolean naturalGround, int fullRadius) {
        Limits(int maxBlocks, int maxRadius, int maxHeight, int minLeaves, boolean naturalGround) {
            this(maxBlocks, maxRadius, maxHeight, minLeaves, naturalGround, maxRadius);
        }

        static Limits of(TimberOptions options) {
            return new Limits(options.maxBlocks(), options.maxRadius(), options.maxHeight(), options.minLeaves(),
                    options.naturalGround());
        }

        /** Beyond this scan's radius but inside the configured one: the caller should scan wider. */
        boolean widerViewHelps(int x, int z, int ox, int oz) {
            int dx = Math.abs(x - ox), dz = Math.abs(z - oz);
            return (dx > maxRadius || dz > maxRadius) && dx <= fullRadius && dz <= fullRadius;
        }
    }

    /**
     * Result of a scan. Positions are packed with {@link #pack}. {@code slice} is the trunk cross-section at the cut
     * height {@code cutY}; the tree pivots around ({@code pivotX}, {@code cutY}, {@code pivotZ}).
     */
    record Result(Verdict verdict, String family, List<Long> logs, List<Long> crown, List<Long> attachments,
            List<Long> slice, int cutY, double pivotX, double pivotZ, int markedBlocks,
            TimberOptions.SizeClass size) {
        static Result rejected(Verdict verdict) {
            return new Result(verdict, null, List.of(), List.of(), List.of(), List.of(), 0, 0, 0, 0,
                    TimberOptions.SizeClass.SMALL);
        }
        boolean tree() { return verdict == Verdict.TREE; }
        int blocks() { return logs.size() + crown.size() + attachments.size(); }
    }

    static final int ROOT_DEPTH = 16, SLICE_LIMIT = 1024, LEAF_REACH = 7, MARKED_LEAF_REACH = 12, CAP_REACH = 48,
            ATTACHMENT_LIMIT = 8192, BASE_SEARCH_LIMIT = 4096;

    // ---- packed positions (same layout as Minecraft's BlockPos#asLong) ----

    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
    static int x(long packed) { return (int) (packed >> 38); }
    static int y(long packed) { return (int) (packed << 52 >> 52); }
    static int z(long packed) { return (int) (packed << 26 >> 38); }
    private static long column(int x, int z) { return pack(x, 0, z); }

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /**
     * Scans the tree of the log at the origin. With {@link TimberOptions.Trigger#FIRST_LOG} the cut is the trunk base
     * (the whole tree comes down); with {@link TimberOptions.Trigger#PERCENT} the cut is the chopped height and the
     * stump below stays.
     */
    static Result scan(View view, int ox, int oy, int oz, TimberOptions.Trigger trigger, Limits limits) {
        Cell origin = view.at(ox, oy, oz);
        String family = origin == null ? null : TimberBlocks.family(origin.type());
        if (family == null) return Result.rejected(Verdict.NOT_A_LOG);

        int baseX = ox, baseY = oy, baseZ = oz;
        boolean grounded;
        if (trigger == TimberOptions.Trigger.FIRST_LOG) {
            long base = findBase(view, family, ox, oy, oz, limits);
            if (base == Long.MIN_VALUE) return Result.rejected(Verdict.OUTSIDE_VIEW);
            baseX = x(base); baseY = y(base); baseZ = z(base);
        }
        int cutY = baseY;

        // Trunk cross-section at the cut height (8-connected).
        List<Long> slice = new ArrayList<>();
        Set<Long> sliceColumns = new HashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        long start = pack(baseX, cutY, baseZ);
        queue.add(start); seen.add(start);
        while (!queue.isEmpty()) {
            long p = queue.poll();
            slice.add(p);
            sliceColumns.add(column(x(p), z(p)));
            if (slice.size() > SLICE_LIMIT) return Result.rejected(Verdict.TOO_LARGE);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = x(p) + dx, nz = z(p) + dz;
                long n = pack(nx, cutY, nz);
                if (seen.contains(n)) continue;
                Cell cell = view.at(nx, cutY, nz);
                if (Math.abs(nx - ox) > limits.maxRadius() || Math.abs(nz - oz) > limits.maxRadius()) {
                    if (cell != null && family.equals(TimberBlocks.family(cell.type())) && limits.widerViewHelps(nx, nz, ox, oz))
                        return Result.rejected(Verdict.OUTSIDE_VIEW);
                    continue;
                }
                if (cell != null && family.equals(TimberBlocks.family(cell.type()))) { seen.add(n); queue.add(n); }
            }
        }

        // A lying trunk (most of the cross-section sideways logs) is a build, not a tree.
        int lying = 0, directional = 0, markedBlocks = 0;
        for (long p : slice) {
            Cell cell = view.at(x(p), y(p), z(p));
            if (cell == null) continue;
            if (cell.marked()) markedBlocks++;
            if (TimberBlocks.directional(cell.type()) && cell.axis() != null) {
                directional++;
                if (cell.axis() != Axis.Y) lying++;
            }
        }
        if (directional > 0 && lying * 2 > directional) return Result.rejected(Verdict.LYING_TRUNK);

        // The trunk must stand on natural ground (through its stump and roots), unless CustomTrees grew it.
        grounded = markedBlocks > 0 || !limits.naturalGround();
        for (int i = 0; i < slice.size() && !grounded; i++) {
            long p = slice.get(i);
            int y = cutY - 1;
            Cell below = view.at(x(p), y, z(p));
            for (int steps = 0; below != null && family.equals(TimberBlocks.family(below.type())) && steps < 64; steps++) {
                below = view.at(x(p), --y, z(p));
            }
            if (below != null && TimberBlocks.ground(below.type())) grounded = true;
        }
        if (!grounded) return Result.rejected(Verdict.NOT_GROUNDED);

        // Logs: everything connected above the cut, plus visible logs below it outside the trunk columns (low or
        // drooping branches, prop and buttress roots), but never the stump or roots buried in the ground.
        List<Long> logs = new ArrayList<>(slice);
        Set<Long> logSet = new HashSet<>(slice);
        queue.clear();
        queue.addAll(slice);
        while (!queue.isEmpty()) {
            long p = queue.poll();
            int px = x(p), py = y(p), pz = z(p);
            for (int[] face : FACES) {
                Cell neighbour = view.at(px + face[0], py + face[1], pz + face[2]);
                if (neighbour != null && !TimberBlocks.natural(neighbour.type())) return Result.rejected(Verdict.FOREIGN_BLOCK);
            }
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                int nx = px + dx, ny = py + dy, nz = pz + dz;
                long n = pack(nx, ny, nz);
                if (logSet.contains(n)) continue;
                Cell cell = view.at(nx, ny, nz);
                if (cell == null) {
                    if (ny >= cutY && withinBounds(nx, ny, nz, ox, oz, cutY, limits)) return Result.rejected(Verdict.OUTSIDE_VIEW);
                    continue;
                }
                if (!family.equals(TimberBlocks.family(cell.type()))) continue;
                if (ny < cutY && (ny < cutY - ROOT_DEPTH || sliceColumns.contains(column(nx, nz)) || !exposed(view, nx, ny, nz))) continue;
                if (!withinBounds(nx, ny, nz, ox, oz, cutY, limits)) {
                    boolean heightOk = ny - cutY <= limits.maxHeight() && ny >= cutY - ROOT_DEPTH;
                    return Result.rejected(heightOk && limits.widerViewHelps(nx, nz, ox, oz) ? Verdict.OUTSIDE_VIEW : Verdict.TOO_LARGE);
                }
                logSet.add(n);
                logs.add(n);
                if (cell.marked()) markedBlocks++;
                if (logs.size() > limits.maxBlocks()) return Result.rejected(Verdict.TOO_LARGE);
                queue.add(n);
            }
        }

        // Crown: leaves by their decay distance, caps by connection.
        List<Long> crown = new ArrayList<>();
        Set<Long> crownSet = new HashSet<>();
        Map<Long, Integer> depth = new HashMap<>();
        boolean caps = TimberBlocks.capFamily(family);
        ArrayDeque<Long> frontier = new ArrayDeque<>(logs);
        for (long p : logs) depth.put(p, 0);
        while (!frontier.isEmpty()) {
            long p = frontier.poll();
            int d = depth.get(p) + 1;
            for (int[] face : FACES) {
                int nx = x(p) + face[0], ny = y(p) + face[1], nz = z(p) + face[2];
                long n = pack(nx, ny, nz);
                if (depth.containsKey(n)) continue;
                Cell cell = view.at(nx, ny, nz);
                if (cell == null || ny < cutY - ROOT_DEPTH) continue;
                if (Math.abs(nx - ox) > limits.maxRadius() + LEAF_REACH || Math.abs(nz - oz) > limits.maxRadius() + LEAF_REACH
                        || ny - cutY > limits.maxHeight() + LEAF_REACH) continue;
                boolean take;
                if (caps) {
                    take = TimberBlocks.capOf(cell.type(), family) && d <= CAP_REACH;
                } else if (TimberBlocks.leaves(cell.type())) {
                    take = cell.marked() ? d <= MARKED_LEAF_REACH
                            : !cell.persistent() && d <= LEAF_REACH && cell.distance() >= Math.min(d, 7);
                } else take = false;
                if (!take) continue;
                depth.put(n, d);
                crown.add(n);
                crownSet.add(n);
                if (cell.marked()) markedBlocks++;
                if (logs.size() + crown.size() > limits.maxBlocks()) return Result.rejected(Verdict.TOO_LARGE);
                frontier.add(n);
            }
        }
        if (crown.size() < limits.minLeaves()) return Result.rejected(Verdict.NO_CROWN);

        // Attached blocks: vines, cocoa, hanging moss, snow on top, and glowstone CustomTrees grew into the crown.
        List<Long> attachments = new ArrayList<>();
        Set<Long> attachmentSet = new HashSet<>();
        ArrayDeque<Long> hanging = new ArrayDeque<>();
        hanging.addAll(logs);
        hanging.addAll(crown);
        while (!hanging.isEmpty() && attachments.size() < ATTACHMENT_LIMIT) {
            long p = hanging.poll();
            for (int[] face : FACES) {
                int nx = x(p) + face[0], ny = y(p) + face[1], nz = z(p) + face[2];
                long n = pack(nx, ny, nz);
                if (attachmentSet.contains(n) || logSet.contains(n) || crownSet.contains(n) || ny < cutY) continue;
                Cell cell = view.at(nx, ny, nz);
                if (cell == null) continue;
                Material type = cell.type();
                boolean take = type == Material.GLOWSTONE ? cell.marked()
                        : type == Material.SNOW ? face[1] == 1
                        : TimberBlocks.attachment(type);
                if (!take) continue;
                attachmentSet.add(n);
                attachments.add(n);
                hanging.add(n);
            }
        }

        double sx = 0, sz = 0;
        for (long p : slice) { sx += x(p) + 0.5; sz += z(p) + 0.5; }
        int total = logs.size() + crown.size() + attachments.size();
        return new Result(Verdict.TREE, family, List.copyOf(logs), List.copyOf(crown), List.copyOf(attachments),
                List.copyOf(slice), cutY, sx / slice.size(), sz / slice.size(), markedBlocks,
                TimberOptions.SizeClass.of(slice.size(), total));
    }

    private static boolean withinBounds(int x, int y, int z, int ox, int oz, int cutY, Limits limits) {
        return Math.abs(x - ox) <= limits.maxRadius() && Math.abs(z - oz) <= limits.maxRadius()
                && y - cutY <= limits.maxHeight() && y >= cutY - ROOT_DEPTH;
    }

    /** A log with air, leaves or plants on one face (above ground); buried roots have none. */
    private static boolean exposed(View view, int x, int y, int z) {
        for (int[] face : FACES) {
            Cell cell = view.at(x + face[0], y + face[1], z + face[2]);
            if (cell != null && TimberBlocks.open(cell.type())) return true;
        }
        return false;
    }

    /**
     * Trunk base for a first-log fell: straight down from the chopped log while the trunk continues; if that column
     * does not end on ground (the chopped log was a branch), the lowest connected log that stands on ground. Returns
     * {@code Long.MIN_VALUE} when the search needs blocks outside the view.
     */
    static long findBase(View view, String family, int ox, int oy, int oz, Limits limits) {
        int y = oy;
        for (int steps = 0; steps < limits.maxHeight(); steps++) {
            Cell below = view.at(ox, y - 1, oz);
            if (below == null) return Long.MIN_VALUE;
            if (!family.equals(TimberBlocks.family(below.type()))) break;
            y--;
        }
        Cell under = view.at(ox, y - 1, oz);
        if (under != null && TimberBlocks.ground(under.type())) return pack(ox, y, oz);
        // The chopped log is a branch or hangs in the air: look for the lowest log of the tree on the ground.
        ArrayDeque<Long> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        long start = pack(ox, oy, oz);
        queue.add(start); seen.add(start);
        long best = Long.MIN_VALUE, lowest = pack(ox, y, oz);
        int bestY = Integer.MAX_VALUE;
        while (!queue.isEmpty() && seen.size() < BASE_SEARCH_LIMIT) {
            long p = queue.poll();
            int px = x(p), py = y(p), pz = z(p);
            Cell floor = view.at(px, py - 1, pz);
            if (floor != null && TimberBlocks.ground(floor.type()) && py < bestY) { best = p; bestY = py; }
            if (py < y(lowest)) lowest = p;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                int nx = px + dx, ny = py + dy, nz = pz + dz;
                if (Math.abs(nx - ox) > limits.maxRadius() || Math.abs(nz - oz) > limits.maxRadius() || oy - ny > limits.maxHeight()) continue;
                long n = pack(nx, ny, nz);
                if (seen.contains(n)) continue;
                Cell cell = view.at(nx, ny, nz);
                if (cell != null && family.equals(TimberBlocks.family(cell.type()))) { seen.add(n); queue.add(n); }
            }
        }
        return best != Long.MIN_VALUE ? best : lowest;
    }
}
