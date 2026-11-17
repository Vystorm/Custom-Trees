package at.esoren.customtrees;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import java.lang.reflect.Method;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

/**
 * Optional CoreProtect access for the timber, by guarded reflection (no compile dependency): looks up whether a player
 * placed the trunk logs, and logs removals the timber did not announce with a per-block break event. Any failure turns
 * the bridge off quietly; the timber then works without it.
 */
final class TimberCoreProtect {
    private Object api;
    private Method blockLookup, parseResult, actionId, user, logRemoval;
    private boolean broken;

    private synchronized boolean resolve() {
        if (api != null) return true;
        if (broken) return false;
        Plugin plugin = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (plugin == null || !plugin.isEnabled()) return false;
        try {
            Object resolved = plugin.getClass().getMethod("getAPI").invoke(plugin);
            if (!Boolean.TRUE.equals(resolved.getClass().getMethod("isEnabled").invoke(resolved))) return false;
            blockLookup = resolved.getClass().getMethod("blockLookup", Block.class, int.class);
            parseResult = resolved.getClass().getMethod("parseResult", String[].class);
            Class<?> parse = parseResult.getReturnType();
            actionId = parse.getMethod("getActionId");
            user = parse.getMethod("getPlayer");
            logRemoval = resolved.getClass().getMethod("logRemoval", String.class, Location.class, Material.class, BlockData.class);
            api = resolved;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            broken = true;
            DebugMessages.mid("[CustomTrees/Timber] CoreProtect API not usable for the timber: " + failure);
            return false;
        }
    }

    boolean available() { return resolve(); }

    /**
     * Whether a player placed any of these logs (their newest CoreProtect entry is a placement by a player). Runs the
     * database lookups, so call it off the server thread.
     */
    TimberGuards.History history(World world, List<Long> logs) {
        if (!resolve()) return TimberGuards.History.UNKNOWN;
        try {
            for (long p : logs) {
                Block block = world.getBlockAt(TimberScanner.x(p), TimberScanner.y(p), TimberScanner.z(p));
                Object rows = blockLookup.invoke(api, block, 0);
                if (!(rows instanceof List<?> list) || list.isEmpty()) continue;
                for (Object row : list) {
                    if (!(row instanceof String[] fields)) continue;
                    Object parsed = parseResult.invoke(api, (Object) fields);
                    int action = ((Number) actionId.invoke(parsed)).intValue();
                    if (action != 0 && action != 1) continue;
                    // The newest break/place entry decides for this block.
                    if (action == 1 && TimberGuards.playerUser(String.valueOf(user.invoke(parsed)))) return TimberGuards.History.PLAYER_PLACED;
                    break;
                }
            }
            return TimberGuards.History.NATURAL;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            DebugMessages.mid("[CustomTrees/Timber] CoreProtect lookup failed: " + failure);
            return TimberGuards.History.UNKNOWN;
        }
    }

    /** Logs one removal as the player (server thread). */
    void logRemoval(String player, Block block, BlockData data) {
        if (!resolve()) return;
        try {
            logRemoval.invoke(api, player, block.getLocation(), data.getMaterial(), data);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            DebugMessages.mid("[CustomTrees/Timber] CoreProtect logging failed: " + failure);
        }
    }
}
