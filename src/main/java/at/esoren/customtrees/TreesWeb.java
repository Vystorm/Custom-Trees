package at.esoren.customtrees;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.web.WebContext;
import Vystorm.vystorm_core.web.WebException;
import Vystorm.vystorm_core.web.WebModule;
import Vystorm.vystorm_core.web.WebRequest;
import Vystorm.vystorm_core.web.WebResponse;
import Vystorm.vystorm_core.web.WebRoute;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Optional web page "trees" (Vystorm Core 0.24.0+, capabilities {@code web} and {@code web-open}): the tree guide for
 * everyone and an admin panel ({@code customtrees.admin}) with the status of {@code /customtrees status} and a reload
 * button. Only this class touches Core's web API.
 *
 * <p>Routes (below {@code /api/trees}):
 * <ul>
 *   <li>{@code GET /guide} – guide data (also published as {@code /data/trees/guide.json}), no permission</li>
 *   <li>{@code GET /admin/status} – status, {@code customtrees.admin}</li>
 *   <li>{@code POST /admin/reload} – same as {@code /customtrees reload}, {@code customtrees.admin}</li>
 * </ul>
 * Live: {@code trees/status} (snapshot and pushes to online admins when a counter or switch changes).
 */
final class TreesWeb {
    static final String MODULE = "trees";
    static final String ADMIN = TreeSettingsEditor.PERMISSION;
    private static final long STATUS_PERIOD_TICKS = 40L;

    private final CustomTreesPlugin plugin;
    private final Lang lang;
    private final Supplier<TreeSettings> settings;
    private BukkitTask statusTask;
    private String lastStatus;
    private boolean registered;

    TreesWeb(CustomTreesPlugin plugin, Lang lang, Supplier<TreeSettings> settings) {
        this.plugin = plugin;
        this.lang = lang;
        this.settings = settings;
    }

    /** Registers the module, publishes the guide and starts the status pushes. Server thread. */
    void register() {
        plugin.core().web().register(plugin, WebModule.builder(MODULE, lang.ref("web.module.title"))
                .description(lang.ref("web.module.description"))
                .route(WebRoute.get("/guide", this::guide).rateLimit(60).build())
                .route(WebRoute.get("/admin/status", this::status).permission(ADMIN).rateLimit(60).build())
                .route(WebRoute.post("/admin/reload", this::reload).permission(ADMIN).rateLimit(6).maxBody(256).build())
                .snapshot(this::snapshot)
                .build());
        registered = true;
        publishGuide();
        statusTask = Bukkit.getScheduler().runTaskTimer(plugin, (Runnable) this::tickStatus, STATUS_PERIOD_TICKS, STATUS_PERIOD_TICKS);
    }

    void unregister() {
        if (statusTask != null) statusTask.cancel();
        statusTask = null;
        if (registered) {
            try {
                plugin.core().web().unregister(plugin, MODULE);
            } catch (RuntimeException ignored) {
                // Core cleans up modules of disabled plugins anyway.
            }
        }
        registered = false;
    }

    private WebResponse guide(WebRequest request) {
        TreeSettings current = settings.get();
        if (current == null) throw new WebException(503, lang.plain(request.language(), "web.error.not-ready"));
        return WebResponse.json(TreesGuide.build(current));
    }

    /** Publishes the guide again (after reload or a settings change); open pages switch to it without polling. */
    void publishGuide() {
        TreeSettings current = settings.get();
        if (!registered || current == null) return;
        try {
            plugin.core().web().publishData(plugin, MODULE, "guide", TreesGuide.build(current));
        } catch (RuntimeException failure) {
            DebugMessages.mid("[CustomTrees web] guide data not published: " + failure);
        }
    }

    private WebResponse status(WebRequest request) {
        return WebResponse.json(request.sync(() -> {
            requireAdmin(request);
            return plugin.status().toJson();
        }));
    }

    private WebResponse reload(WebRequest request) {
        JsonObject status = request.sync(() -> {
            requireAdmin(request);
            String error = plugin.reload(request.language());
            if (error != null) {
                throw WebException.conflict(lang.plain(request.language(), "web.error.reload-failed", "error", error));
            }
            DebugMessages.mid("[CustomTrees web] configuration reloaded by " + request.playerName());
            JsonObject fresh = plugin.status().toJson();
            pushStatus(fresh);
            return fresh;
        });
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.add("status", status);
        return WebResponse.json(result);
    }

    /**
     * Same rule as the in-game command: {@code customtrees.admin}. Core already checked the route permission (for an
     * offline player with the value last measured online); an online player is checked again live. Server thread.
     */
    private void requireAdmin(WebContext context) {
        Player player = Bukkit.getPlayer(context.playerId());
        if (player != null && !player.hasPermission(ADMIN)) {
            throw WebException.forbidden(lang.plain(context.language(), "web.error.no-permission"));
        }
    }

    private Map<String, JsonElement> snapshot(WebContext context) {
        if (!context.online()) return Map.of();
        return context.sync(() -> {
            Player player = Bukkit.getPlayer(context.playerId());
            if (player == null || !player.hasPermission(ADMIN) || settings.get() == null) return Map.of();
            return Map.<String, JsonElement>of("status", plugin.status().toJson());
        });
    }

    /** Pushes the status to online admins when it changed. Server thread. */
    private void tickStatus() {
        if (settings.get() == null) return;
        pushStatus(plugin.status().toJson());
    }

    private void pushStatus(JsonObject status) {
        String text = status.toString();
        if (text.equals(lastStatus)) return;
        lastStatus = text;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(ADMIN)) plugin.core().web().push(player.getUniqueId(), MODULE, "status", status);
        }
    }

    /** Whether the web platform is running (a chat link or the overlay can work). */
    boolean available() {
        try {
            return plugin.core().web().available();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** {@code /customtrees guide}: overlay (client mod), else a chat link, else the in-game tutorial. Server thread. */
    void open(Player player, String page, Runnable fallback) {
        plugin.core().web().openOrLink(player, MODULE, page, fallback);
    }
}
