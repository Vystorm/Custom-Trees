package at.esoren.customtrees;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;

/**
 * Name-based block classes for the timber (works without a running server, so the detection is unit-testable).
 * "Natural" blocks may touch a tree; any other block next to a log marks a player build.
 */
final class TimberBlocks {
    private TimberBlocks() {}

    private static final Set<Material> AIR = of("AIR", "CAVE_AIR", "VOID_AIR");
    private static final Set<Material> CAPS = of("NETHER_WART_BLOCK", "WARPED_WART_BLOCK", "SHROOMLIGHT",
            "RED_MUSHROOM_BLOCK", "BROWN_MUSHROOM_BLOCK");
    /** Blocks hanging on or lying on a tree that go down with it. */
    private static final Set<Material> ATTACHMENTS = of("VINE", "COCOA", "PALE_HANGING_MOSS", "WEEPING_VINES",
            "WEEPING_VINES_PLANT", "TWISTING_VINES", "TWISTING_VINES_PLANT", "GLOW_LICHEN", "SNOW", "RESIN_CLUMP");
    private static final Set<Material> GROUND = of("GRASS_BLOCK", "DIRT", "COARSE_DIRT", "ROOTED_DIRT", "PODZOL",
            "MYCELIUM", "MUD", "MUDDY_MANGROVE_ROOTS", "MANGROVE_ROOTS", "MOSS_BLOCK", "PALE_MOSS_BLOCK", "SAND",
            "RED_SAND", "GRAVEL", "CLAY", "SNOW_BLOCK", "POWDER_SNOW", "DIRT_PATH", "CRIMSON_NYLIUM", "WARPED_NYLIUM",
            "NETHERRACK", "SOUL_SOIL", "SOUL_SAND", "END_STONE", "STONE", "DEEPSLATE", "GRANITE", "DIORITE",
            "ANDESITE", "TUFF", "CALCITE", "SANDSTONE", "RED_SANDSTONE", "TERRACOTTA", "BEDROCK", "ICE", "PACKED_ICE",
            "BLUE_ICE", "BASALT", "BLACKSTONE", "MAGMA_BLOCK", "DRIPSTONE_BLOCK", "SMOOTH_BASALT", "OBSIDIAN");
    private static final Set<Material> PLANTS = of("SHORT_GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "DEAD_BUSH",
            "BUSH", "FIREFLY_BUSH", "SWEET_BERRY_BUSH", "LEAF_LITTER", "SHORT_DRY_GRASS", "TALL_DRY_GRASS",
            "MANGROVE_PROPAGULE", "PINK_PETALS", "WILDFLOWERS", "RED_MUSHROOM", "BROWN_MUSHROOM", "MOSS_CARPET",
            "PALE_MOSS_CARPET", "HANGING_ROOTS", "SEAGRASS", "TALL_SEAGRASS", "KELP", "KELP_PLANT", "LILY_PAD",
            "BIG_DRIPLEAF", "BIG_DRIPLEAF_STEM", "SMALL_DRIPLEAF", "AZALEA", "FLOWERING_AZALEA", "SPORE_BLOSSOM",
            "CRIMSON_ROOTS", "WARPED_ROOTS", "NETHER_SPROUTS", "CRIMSON_FUNGUS", "WARPED_FUNGUS", "POINTED_DRIPSTONE",
            "SUGAR_CANE", "CACTUS", "COBWEB", "BEE_NEST", "CREAKING_HEART", "WATER", "BUBBLE_COLUMN",
            "DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET", "RED_TULIP", "ORANGE_TULIP", "WHITE_TULIP",
            "PINK_TULIP", "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY", "SUNFLOWER", "LILAC", "ROSE_BUSH",
            "PEONY", "OPEN_EYEBLOSSOM", "CLOSED_EYEBLOSSOM", "CACTUS_FLOWER", "GLOW_BERRIES", "CAVE_VINES",
            "CAVE_VINES_PLANT", "AMETHYST_CLUSTER", "SCULK", "SCULK_VEIN", "SEA_PICKLE");

    private static Set<Material> of(String... names) {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (String name : names) {
            Material material = Material.getMaterial(name);
            if (material != null) set.add(material);
        }
        return set;
    }

    /**
     * Wood family of a log, wood, stem or hyphae block ({@code OAK}, {@code CRIMSON}, {@code MUSHROOM}, ...); null for
     * anything else. Stripped wood is never part of a natural tree.
     */
    static String family(Material material) {
        if (material == null) return null;
        String name = material.name();
        if (name.startsWith("STRIPPED_") || name.startsWith("LEGACY_")) return null;
        for (String suffix : new String[] {"_LOG", "_WOOD", "_STEM", "_HYPHAE"}) {
            if (name.endsWith(suffix) && name.length() > suffix.length()) {
                String family = name.substring(0, name.length() - suffix.length());
                return family.equals("PETRIFIED_OAK") ? null : family;
            }
        }
        return null;
    }

    /** Logs and stems (not wood or hyphae) have a direction; a lying trunk is a build, not a tree. */
    static boolean directional(Material material) {
        String name = material.name();
        return name.endsWith("_LOG") || name.endsWith("_STEM");
    }

    static boolean air(Material material) { return material != null && AIR.contains(material); }

    static boolean leaves(Material material) { return material != null && material.name().endsWith("_LEAVES"); }

    /** Crown blocks of huge mushrooms and Nether fungi (no leaf distance). */
    static boolean cap(Material material) { return material != null && CAPS.contains(material); }

    /** Whether a cap block belongs to a tree of this wood family. */
    static boolean capOf(Material material, String family) {
        if (!cap(material) || family == null) return false;
        return switch (material.name()) {
            case "NETHER_WART_BLOCK" -> family.equals("CRIMSON");
            case "WARPED_WART_BLOCK" -> family.equals("WARPED");
            case "SHROOMLIGHT" -> family.equals("CRIMSON") || family.equals("WARPED");
            default -> family.equals("MUSHROOM");
        };
    }

    /** Families whose crown is made of cap blocks instead of leaves. */
    static boolean capFamily(String family) {
        return "CRIMSON".equals(family) || "WARPED".equals(family) || "MUSHROOM".equals(family);
    }

    static boolean attachment(Material material) { return material != null && ATTACHMENTS.contains(material); }

    /** Ground a trunk may stand on. */
    static boolean ground(Material material) {
        if (material == null) return false;
        if (GROUND.contains(material)) return true;
        String name = material.name();
        return (name.endsWith("_TERRACOTTA") && !name.contains("GLAZED")) || name.endsWith("_ORE");
    }

    static boolean plant(Material material) {
        if (material == null) return false;
        if (PLANTS.contains(material)) return true;
        String name = material.name();
        return name.endsWith("_SAPLING") && !name.startsWith("POTTED_");
    }

    /** Blocks that may touch a log of a natural tree. Anything else (planks, glass, torches, chests, ...) is a build. */
    static boolean natural(Material material) {
        if (material == null) return true;
        return air(material) || family(material) != null || leaves(material) || cap(material) || attachment(material)
                || ground(material) || plant(material) || material == Material.GLOWSTONE || material == Material.LAVA;
    }

    /** A neighbour through which a log counts as visible above ground (roots in the soil are not). */
    static boolean open(Material material) {
        return material != null && (air(material) || leaves(material) || cap(material) || attachment(material)
                || plant(material));
    }

    static boolean axe(Material material) {
        return material != null && material.name().endsWith("_AXE");
    }
}
