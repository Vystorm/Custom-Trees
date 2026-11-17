package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructurePos;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeSpecies;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Geometry and checks of the planting helper: a square of {@code size}x{@code size} plants whose first corner is the
 * plant the player placed, growing forward (the player's facing) and to the player's right. Pure logic, no world access.
 */
final class PlantingStencil {
    /** Square sizes that match CustomTrees formations (2x2 medium, 3x3 giant, 5x5 colossus, 10x10 world tree). */
    static final List<Integer> SIZES = List.of(2, 3, 5, 10);

    private PlantingStencil() {}

    /** {@code planting-helper.*} in config.yml. */
    record Options(boolean enabled, int maxSize, boolean keepActive, long timeoutMillis, int maxHedgeLength) {
        static Options read(FileConfiguration config) {
            int requested = config.getInt("planting-helper.max-size", 10);
            int max = SIZES.getFirst();
            for (int size : SIZES) if (size <= requested) max = size;
            return new Options(config.getBoolean("planting-helper.enabled", true), max,
                    config.getBoolean("planting-helper.keep-active", false),
                    Math.clamp(config.getLong("planting-helper.timeout-seconds", 300), 10, 3600) * 1000L,
                    Math.clamp(config.getInt("planting-helper.max-hedge-length", 16), 0, 32));
        }

        /** Sizes offered on this server. */
        List<Integer> sizes() { return SIZES.stream().filter(size -> size <= maxSize).toList(); }
    }

    record Pattern(int size, boolean hedge) {
        static Pattern parse(String text) {
            if (text != null && text.toLowerCase(Locale.ROOT).startsWith("hedge")) {
                try {
                    int length = Integer.parseInt(text.substring(5));
                    return length >= 4 && length <= 32 ? new Pattern(length, true) : null;
                } catch (NumberFormatException invalid) { return null; }
            }
            Integer size = parseSize(text);
            return size == null ? null : new Pattern(size, false);
        }

        boolean allowed(Options options) {
            return options.enabled() && size <= (hedge ? options.maxHedgeLength() : options.maxSize());
        }

        boolean accepts(Material material) {
            return hedge ? TreeSpecies.fromSapling(material) != null : plantable(material);
        }

        List<StructurePos> cells(StructurePos origin, int forwardX, int forwardZ) {
            if (!hedge) return PlantingStencil.cells(origin, forwardX, forwardZ, size);
            if (Math.abs(forwardX) + Math.abs(forwardZ) != 1) throw new IllegalArgumentException("facing must be cardinal");
            List<StructurePos> cells = new ArrayList<>(size);
            for (int i = 0; i < size; i++) cells.add(new StructurePos(origin.x() + forwardX * i,
                    origin.y(), origin.z() + forwardZ * i));
            return List.copyOf(cells);
        }

        String key(String message) { return "planting." + (hedge ? "hedge-" : "") + message; }
    }

    enum Cell { SAME, FREE, BLOCKED }

    /** {@code free} cells get a plant, {@code blocked} ones prevent planting, {@code already} hold the plant. */
    record Plan(List<StructurePos> free, List<StructurePos> blocked, int already) {
        boolean ready() { return blocked.isEmpty(); }
    }

    /** "2x2", "3×3", "5" or "10X10" to a supported size; null otherwise ("off" is handled by the caller). */
    static Integer parseSize(String text) {
        if (text == null) return null;
        String value = text.trim().toLowerCase(Locale.ROOT).replace('×', 'x');
        String[] parts = value.split("x", -1);
        if (parts.length > 2 || (parts.length == 2 && !parts[0].equals(parts[1]))) return null;
        try {
            int size = Integer.parseInt(parts[0]);
            return SIZES.contains(size) ? size : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** Saplings, the mangrove propagule, mushrooms and Nether fungi: everything CustomTrees grows from squares. */
    static boolean plantable(Material material) {
        return TreeSpecies.fromSapling(material) != null || material == Material.RED_MUSHROOM
                || material == Material.BROWN_MUSHROOM || material == Material.CRIMSON_FUNGUS
                || material == Material.WARPED_FUNGUS;
    }

    /**
     * Cells of the square, origin first. {@code (forwardX, forwardZ)} is the player's horizontal facing (one of the
     * four cardinal unit vectors); the square extends forward and to the right of it.
     */
    static List<StructurePos> cells(StructurePos origin, int forwardX, int forwardZ, int size) {
        if (Math.abs(forwardX) + Math.abs(forwardZ) != 1) throw new IllegalArgumentException("facing must be cardinal");
        int rightX = -forwardZ, rightZ = forwardX;
        List<StructurePos> cells = new ArrayList<>(size * size);
        for (int forward = 0; forward < size; forward++) {
            for (int right = 0; right < size; right++) {
                cells.add(new StructurePos(origin.x() + forwardX * forward + rightX * right, origin.y(),
                        origin.z() + forwardZ * forward + rightZ * right));
            }
        }
        return cells;
    }

    /** Sorts the cells by their state; the plan is ready only when no cell is blocked. */
    static Plan plan(List<StructurePos> cells, Function<StructurePos, Cell> state) {
        List<StructurePos> free = new ArrayList<>(), blocked = new ArrayList<>();
        int already = 0;
        for (StructurePos cell : cells) {
            switch (state.apply(cell)) {
                case SAME -> already++;
                case FREE -> free.add(cell);
                case BLOCKED -> blocked.add(cell);
            }
        }
        return new Plan(List.copyOf(free), List.copyOf(blocked), already);
    }

    /** Status line of {@code /customtrees status}. */
    static String statusLine(Options options, long squares, long plants, int armed) {
        return String.format(Locale.ROOT, "CustomTrees planting-helper: enabled=%s, max-size=%d, keep-active=%s, squares=%d, plants=%d, active-now=%d",
                options.enabled(), options.maxSize(), options.keepActive(), squares, plants, armed);
    }
}
