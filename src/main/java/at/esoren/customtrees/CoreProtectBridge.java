package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructureAccess;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructurePos;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeSpecies;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.concurrent.atomic.LongAdder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

final class CoreProtectBridge {
    private static final String CORE_PROTECT_PLUGIN = "CoreProtect";
    private static final String LOG_ACTOR = "CustomTrees";

    private final CustomTreesPlugin plugin;
    private final LongAdder loggedBlocks = new LongAdder();
    private Object api;
    private Method logPlacement;
    private boolean incompatible;
    private boolean activationLogged;
    private boolean warningLogged;
    private long lastFailureWarning;

    CoreProtectBridge(CustomTreesPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Logs protected trunks and tree glowstone as {@code CustomTrees}
     * placements. CoreProtect already logs the growth itself through the
     * StructureGrowEvent; SmoothTimber answers that event five ticks later
     * with "#" removals so grown trees stay fellable. This call therefore
     * runs after that (see {@link GiantSaplingListener}), so the newest
     * entry of a protected trunk is our placement and SmoothTimber leaves it
     * standing.
     */
    int logPlacements(World world, Collection<StructurePos> positions) {
        if (!resolve()) {
            return 0;
        }
        int logged = 0;
        try {
            for (StructurePos position : positions) {
                if (!world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) {
                    continue;
                }
                Block block = world.getBlockAt(position.x(), position.y(), position.z());
                Material type = block.getType();
                if (!TreeSpecies.isLog(type) && type != Material.GLOWSTONE && type != Material.MUSHROOM_STEM
                        && type != Material.CRIMSON_STEM && type != Material.WARPED_STEM) {
                    continue;
                }
                Object result = logPlacement.invoke(api, LOG_ACTOR, block.getLocation(), type, block.getBlockData());
                if (Boolean.TRUE.equals(result)) {
                    logged++;
                }
            }
        } catch (IllegalAccessException exception) {
            incompatible = true;
            warn("Installed CoreProtect API is incompatible: " + rootMessage(exception));
        } catch (InvocationTargetException exception) {
            // A single failing call must not disable protection for the rest of the run.
            warn("CoreProtect logging failed, will retry with the next tree: " + rootMessage(exception));
        }
        loggedBlocks.add(logged);
        return logged;
    }

    long loggedBlocks() {
        return loggedBlocks.sum();
    }

    private boolean resolve() {
        if (api != null) {
            return true;
        }
        if (incompatible) {
            return false;
        }
        Plugin coreProtect = Bukkit.getPluginManager().getPlugin(CORE_PROTECT_PLUGIN);
        if (coreProtect == null || !coreProtect.isEnabled()) {
            warnOnce("CoreProtect is unavailable; protected custom trees cannot be marked for SmoothTimber protection.");
            return false;
        }
        try {
            Object resolvedApi = coreProtect.getClass().getMethod("getAPI").invoke(coreProtect);
            Method isEnabled = resolvedApi.getClass().getMethod("isEnabled");
            if (!Boolean.TRUE.equals(isEnabled.invoke(resolvedApi))) {
                warnOnce("CoreProtect API is disabled; enable api-enabled for custom-tree protection.");
                return false;
            }
            Method resolvedLogPlacement = resolvedApi.getClass().getMethod("logPlacement",
                    String.class, Location.class, Material.class, BlockData.class);
            api = resolvedApi;
            logPlacement = resolvedLogPlacement;
            if (!activationLogged) {
                activationLogged = true;
                DebugMessages.mid(
                        "[CoreProtectBridge 93] CoreProtect custom-tree logging is active; SmoothTimber will ignore protected trunks.");
            }
            return true;
        } catch (ReflectiveOperationException exception) {
            incompatible = true;
            warn("Installed CoreProtect API is incompatible: " + rootMessage(exception));
            return false;
        }
    }

    /** Visible warning for real failures, at most once per minute. */
    private void warn(String message) {
        long now = System.currentTimeMillis();
        if (now - lastFailureWarning >= 60_000L) {
            lastFailureWarning = now;
            DebugMessages.high("[CoreProtectBridge] " + message);
        }
    }

    private void warnOnce(String message) {
        if (!warningLogged) {
            warningLogged = true;
            DebugMessages.mid("[CoreProtectBridge 106] " + message);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
