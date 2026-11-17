package at.esoren.customtrees.acceptance;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.main;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.*;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeSpecies;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;

/** Explicitly enabled, isolated loopback test server only. Never deploy to production. */
public final class StructureAcceptance extends JavaPlugin {
    private int checks;

    @Override public void onEnable() {
        if (!Boolean.getBoolean("customtrees.integration.enabled")
                || !getServer().getIp().equals("127.0.0.1") || getServer().getPort() != 25585) {
            throw new IllegalStateException("Use an isolated loopback test server on port 25585");
        }
        getServer().getScheduler().runTaskLater(this, () -> {
            try {
                verify();
                getLogger().info("STRUCTURE ACCEPTANCE PASSED checks=" + checks);
            } catch (Throwable failure) {
                getLogger().log(Level.SEVERE, "STRUCTURE ACCEPTANCE FAILED", failure);
            } finally {
                getServer().getScheduler().runTaskLater(this, () -> getServer().shutdown(), 5L);
            }
        }, 10L);
    }

    private void verify() throws Exception {
        API api = main.getApi();
        check(api != null && getServer().getPluginManager().isPluginEnabled("CustomTrees"), "bootstrap");
        check(getServer().getPluginManager().getPlugin("vystorm_core").getDescription().getLoad()
                == org.bukkit.plugin.PluginLoadOrder.STARTUP, "Core available in STARTUP phase");
        StructureGeneratorInterface handler = api.structureGenerator();
        NamespacedKey key = new NamespacedKey(this, "stone_marker");
        World world = getServer().getWorlds().getFirst();
        world.getChunkAt(0, 0).load();
        StructurePos origin = new StructurePos(3, 180, 3);
        StructureAccess worldAccess = new StructureAccess() {
            public boolean contains(int x, int y, int z) { return x >= 0 && x < 16 && z >= 0 && z < 16 && y >= 175 && y < 190; }
            public Material getType(int x, int y, int z) { return contains(x,y,z) ? world.getBlockAt(x,y,z).getType() : Material.AIR; }
            public void setType(int x, int y, int z, Material m) { if (contains(x,y,z)) world.getBlockAt(x,y,z).setType(m, false); }
            public void setBlockData(int x, int y, int z, BlockData d) { if (contains(x,y,z)) world.getBlockAt(x,y,z).setBlockData(d, false); }
        };
        Thread caller = Thread.currentThread();
        handler.register(this, key, (access, pos, random) -> {
            if (Thread.currentThread() != caller) throw new AssertionError("Unexpected thread switch");
            access.setType(pos.x(), pos.y(), pos.z(), Material.STONE);
        });
        check(handler.generate(key, worldAccess, origin, new Random(1))
                && world.getBlockAt(3, 180, 3).getType() == Material.STONE, "custom generator places real block");
        handler.removeAll(this);
        check(!handler.generate(key, worldAccess, origin, new Random(1)), "owner cleanup");
        handler.register(this, key, (access, pos, random) -> {});
        StructureGeneratorHandler replacement = new StructureGeneratorHandler();
        api.overrideStructureGeneratorHandler(replacement);
        check(api.structureGenerator() == replacement && handler.registeredNames().isEmpty(), "handler override and old registry cleanup");
        api.overrideStructureGeneratorHandler(handler);

        // Compare the actual previous release bytecode against Core with identical seeds and input.
        try (URLClassLoader legacy = new URLClassLoader(
                new java.net.URL[] {Path.of("test-data/CustomTrees-1.18.0-baseline.jar").toUri().toURL()}, getClassLoader())) {
            Class<?> accessType = legacy.loadClass("at.esoren.customtrees.TreeAccess");
            Class<?> posType = legacy.loadClass("at.esoren.customtrees.TreePos");
            Class<?> speciesType = legacy.loadClass("at.esoren.customtrees.TreeSpecies");
            Class<?> builderType = legacy.loadClass("at.esoren.customtrees.TreeBuilder");
            Constructor<?> posConstructor = posType.getDeclaredConstructor(int.class, int.class, int.class);
            posConstructor.setAccessible(true);
            Constructor<?> builderConstructor = builderType.getDeclaredConstructor(accessType, Random.class, speciesType);
            builderConstructor.setAccessible(true);
            Method valueOf = speciesType.getDeclaredMethod("valueOf", String.class);
            valueOf.setAccessible(true);
            for (TreeSpecies species : TreeSpecies.values()) {
                Object oldSpecies = valueOf.invoke(null, species.name());
                for (int size = 0; size < 6; size++) {
                    for (int seedIndex = 0; seedIndex < (size < 4 ? 4 : 1); seedIndex++) {
                        long seed = 17 + seedIndex * 7919L;
                        MemoryAccess expected = new MemoryAccess(), actual = new MemoryAccess();
                        Object oldAccess = Proxy.newProxyInstance(legacy, new Class<?>[] {accessType}, (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getType" -> expected.getType((int) args[0], (int) args[1], (int) args[2]);
                                    case "contains" -> expected.contains((int) args[0], (int) args[1], (int) args[2]);
                                    case "getLightLevel" -> 15;
                                    case "setType" -> { expected.setType((int) args[0], (int) args[1], (int) args[2], (Material) args[3]); yield null; }
                                    case "setBlockData" -> { expected.setBlockData((int) args[0], (int) args[1], (int) args[2], (BlockData) args[3]); yield null; }
                                    default -> throw new UnsupportedOperationException(method.getName());
                                });
                        Random oldRandom = new Random(seed), newRandom = new Random(seed);
                        Object oldBuilder = builderConstructor.newInstance(oldAccess, oldRandom, oldSpecies);
                        Object oldOrigin = posConstructor.newInstance(0, 64, 0);
                        TreeGenerator builder = handler.tree(actual, newRandom, species);
                        StructurePos base = new StructurePos(0, 64, 0);
                        String method = switch (size) { case 0 -> "buildNormal"; case 1 -> "buildMedium"; case 2 -> "buildGiant"; case 3 -> "buildColossal"; default -> "buildConnectedPlant"; };
                        if (size < 2) {
                            invoke(builderType, oldBuilder, method, new Class<?>[] {posType}, oldOrigin);
                            if (size == 0) builder.buildNormal(base); else builder.buildMedium(base);
                        } else if (size < 4) {
                            int min = size == 2 ? 36 : 52, max = size == 2 ? 48 : 68, radius = size == 2 ? 11 : 17;
                            invoke(builderType, oldBuilder, method, new Class<?>[] {posType, int.class, int.class, int.class}, oldOrigin, min, max, radius);
                            if (size == 2) builder.buildGiant(base, min, max, radius); else builder.buildColossal(base, min, max, radius);
                        } else {
                            List<StructurePos> positions = size == 4 ? List.of(base, base.add(1, 0, 0))
                                    : List.of(base, base.add(1, 0, 0), base.add(2, 0, 1), base.add(2, 0, 2));
                            List<Object> oldPositions = new ArrayList<>();
                            for (StructurePos pos : positions) oldPositions.add(posConstructor.newInstance(pos.x(), pos.y(), pos.z()));
                            invoke(builderType, oldBuilder, method, new Class<?>[] {List.class, boolean.class}, oldPositions, size == 5);
                            builder.buildConnectedPlant(positions, size == 5);
                        }
                        check(!actual.blocks.isEmpty() && expected.snapshot().equals(actual.snapshot())
                                && oldRandom.nextLong() == newRandom.nextLong(), species + " " + method + " seed=" + seed);
                    }
                }
            }
        }
        MemoryAccess leaves = new MemoryAccess();
        Set<StructurePos> positions = new HashSet<>();
        for (int x = 0; x < 20; x++) {
            StructurePos pos = new StructurePos(x * 4, 70, 0);
            positions.add(pos); leaves.setType(pos.x(), pos.y(), pos.z(), Material.OAK_LEAVES);
        }
        check(handler.decorateGlowstone(leaves, positions, TreeSpecies.OAK, new Random(1), 16, 2, 7) == 2,
                "glowstone decoration through API");
    }

    private static void invoke(Class<?> type, Object instance, String name, Class<?>[] parameters, Object... args) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        method.invoke(instance, args);
    }

    private void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
        getLogger().info("PASS " + name);
    }

    private static final class MemoryAccess implements StructureAccess {
        final Map<StructurePos, BlockData> blocks = new HashMap<>();
        public boolean contains(int x, int y, int z) { return x >= -96 && x <= 96 && z >= -96 && z <= 96 && y >= -64 && y < 320; }
        public Material getType(int x, int y, int z) {
            BlockData data = blocks.get(new StructurePos(x,y,z)); return data == null ? Material.AIR : data.getMaterial();
        }
        public void setType(int x, int y, int z, Material material) { setBlockData(x, y, z, material.createBlockData()); }
        public void setBlockData(int x, int y, int z, BlockData data) {
            if (!contains(x,y,z)) throw new AssertionError("Out-of-bounds write");
            StructurePos pos = new StructurePos(x,y,z);
            if (data.getMaterial().isAir()) blocks.remove(pos); else blocks.put(pos, data.clone());
        }
        Map<StructurePos, String> snapshot() {
            Map<StructurePos, String> result = new HashMap<>();
            blocks.forEach((pos, data) -> result.put(pos, data.getAsString()));
            return result;
        }
    }
}
