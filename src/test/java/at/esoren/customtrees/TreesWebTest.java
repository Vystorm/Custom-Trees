package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.web.WebException;
import Vystorm.vystorm_core.web.WebMethod;
import Vystorm.vystorm_core.web.WebModule;
import Vystorm.vystorm_core.web.WebRequest;
import Vystorm.vystorm_core.web.WebResponse;
import Vystorm.vystorm_core.web.WebRoute;
import Vystorm.vystorm_core.web.WebServices;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class TreesWebTest {
    static TreeSettings defaults() {
        try (var stream = TreesWebTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            return TreeSettings.load(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    static TreesStatus sample(TreeSettings settings) {
        return new TreesStatus("1.24.0-26.2", settings, 5, 2, 1, 3, 4, 6, 7, 99, 120, 400, 38, 0, List.of("CoreProtect"));
    }

    @Test
    void statusChatLineKeepsTheFormatOfEarlierVersions() {
        assertEquals("CustomTrees: player-grown=true, grown=5, giant-grown=2, colossal-grown=1, shrubs=3, hedges=4, "
                        + "glowstone-grown=6, coal-grown=7, glowstone-blocks=99, coreprotect-logs=120, "
                        + "replace-generated=true (100%), new-chunk-pass=true, inspected-chunks=400, replaced=38, "
                        + "queued=0, giant-3x3=true, colossal-5x5=true, worlds=all normal worlds",
                sample(defaults()).commandLine("all normal worlds"));
        JsonObject json = sample(defaults()).toJson();
        assertEquals(12, json.getAsJsonObject("counters").size());
        assertEquals(38, json.getAsJsonObject("counters").get("replaced").getAsInt());
        assertTrue(json.getAsJsonObject("switches").get("glowstone").getAsBoolean());
        assertEquals("CoreProtect", json.getAsJsonArray("integrations").get(0).getAsString());
        assertTrue(json.getAsJsonObject("speciesGrown").isEmpty(), "legacy construction defaults the new field empty");
    }

    @Test
    void speciesCountersAreExposedAsANineSpeciesBoundedBreakdown() {
        Map<String, Long> species = new java.util.LinkedHashMap<>();
        species.put("oak", 8L); species.put("birch", 7L); species.put("spruce", 6L);
        species.put("jungle", 5L); species.put("acacia", 4L); species.put("dark_oak", 3L);
        species.put("mangrove", 2L); species.put("cherry", 1L); species.put("pale_oak", 0L);
        TreesStatus status = new TreesStatus("1.24.2-26.2", defaults(), 36, 2, 1, 3, 4, 6, 7,
                99, 120, 400, 38, 0, List.of(), species);
        JsonObject json = status.toJson();
        assertEquals(9, json.getAsJsonObject("speciesGrown").size());
        assertEquals(8, json.getAsJsonObject("speciesGrown").get("oak").getAsInt());
        assertEquals(13, json.getAsJsonObject("counters").size());
        assertTrue(json.getAsJsonObject("counters").has("species-grown"));
        assertTrue(status.commandLine("all normal worlds").endsWith(
                "species-grown=oak:8,birch:7,spruce:6,jungle:5,acacia:4,dark_oak:3,mangrove:2,cherry:1,pale_oak:0"));
    }

    @Test
    void guideReflectsTheLoadedConfiguration() {
        JsonObject guide = TreesGuide.build(defaults());
        assertEquals(9, guide.getAsJsonArray("species").size());
        JsonObject oak = guide.getAsJsonArray("species").get(0).getAsJsonObject();
        assertEquals("oak", oak.get("id").getAsString());
        assertEquals("round", oak.get("shape").getAsString());
        assertEquals("oak_sapling", oak.get("sapling").getAsString());
        assertEquals(48, guide.getAsJsonObject("giant").get("maxHeight").getAsInt());
        assertEquals(17, guide.getAsJsonObject("colossal").get("radius").getAsInt());
        assertEquals(100, guide.getAsJsonObject("glowstone").get("xpCost").getAsInt());
        assertEquals(4, guide.getAsJsonObject("shrubs").get("hedgeMin").getAsInt());
        assertEquals(3, guide.getAsJsonObject("shrubs").get("shrubMax").getAsInt());
        assertTrue(guide.getAsJsonObject("generation").get("enabled").getAsBoolean());
        assertEquals(4, guide.getAsJsonArray("variants").size());

        YamlConfiguration off = new YamlConfiguration();
        off.set("settings.custom-player-grown-trees", false);
        off.set("settings.coal-growth", false);
        off.set("settings.enabled-worlds", List.of("World"));
        JsonObject limited = TreesGuide.build(TreeSettings.load(off));
        assertFalse(limited.getAsJsonObject("single").get("enabled").getAsBoolean());
        assertFalse(limited.getAsJsonObject("shrubs").get("enabled").getAsBoolean(), "shrubs need player-grown trees");
        assertFalse(limited.getAsJsonObject("coal").get("enabled").getAsBoolean());
        assertTrue(limited.getAsJsonObject("giant").get("enabled").getAsBoolean());
        assertEquals("world", limited.getAsJsonArray("worlds").get(0).getAsString());
    }

    @Test
    void registersPublicGuideAndAdminRoutesAndPublishesTheGuide() throws Exception {
        try (Fixture f = new Fixture()) {
            WebModule module = f.module;
            assertEquals("trees", module.id());
            assertTrue(module.permission().isEmpty(), "the guide is for everyone");
            WebRoute guide = f.route(WebMethod.GET, "/guide");
            assertTrue(guide.permission().isEmpty());
            assertEquals("customtrees.admin", f.route(WebMethod.GET, "/admin/status").permission().orElseThrow());
            WebRoute reload = f.route(WebMethod.POST, "/admin/reload");
            assertEquals("customtrees.admin", reload.permission().orElseThrow());
            assertTrue(reload.mutating());
            assertEquals(6, reload.ratePerMinute());
            assertTrue(reload.maxBodyBytes() <= 256);
            f.web.verify(() -> WebServices.publishData(eq(f.plugin), eq("trees"), eq("guide"), any(JsonElement.class)));

            JsonObject body = f.call(guide);
            assertEquals(9, body.getAsJsonArray("species").size());
        }
    }

    @Test
    void reloadRunsTheCommandPathAndReportsValidationErrors() throws Exception {
        try (Fixture f = new Fixture()) {
            WebRoute reload = f.route(WebMethod.POST, "/admin/reload");
            when(f.plugin.reload("en")).thenReturn(null);
            JsonObject ok = f.call(reload);
            assertTrue(ok.get("ok").getAsBoolean());
            assertEquals(5, ok.getAsJsonObject("status").getAsJsonObject("counters").get("grown").getAsInt());
            f.web.verify(() -> WebServices.push(eq(f.adminId), eq("trees"), eq("status"), any(JsonElement.class)));

            when(f.plugin.reload("en")).thenReturn("Giant heights must satisfy 16 <= min <= max <= 96.");
            WebException failed = assertThrows(WebException.class, () -> f.call(reload));
            assertEquals(409, failed.status());
            assertTrue(failed.getMessage().contains("Giant heights"), failed.getMessage());
            assertTrue(failed.getMessage().startsWith("Reload failed"), failed.getMessage());
        }
    }

    @Test
    void onlinePlayerWhoLostTheAdminPermissionIsRefusedLive() {
        try (Fixture f = new Fixture()) {
            when(f.admin.hasPermission("customtrees.admin")).thenReturn(false);
            WebException denied = assertThrows(WebException.class, () -> f.call(f.route(WebMethod.POST, "/admin/reload")));
            assertEquals(403, denied.status());
            verify(f.plugin, never()).reload(anyString());
            assertEquals(403, assertThrows(WebException.class,
                    () -> f.call(f.route(WebMethod.GET, "/admin/status"))).status());
        }
    }

    @Test
    void statusIsPushedOnlyToOnlineAdminsAndOnlyWhenItChanged() {
        try (Fixture f = new Fixture()) {
            Runnable tick = f.timer.get();
            assertNotNull(tick, "status timer started");
            tick.run();
            tick.run();
            f.web.verify(() -> WebServices.push(eq(f.adminId), eq("trees"), eq("status"), any(JsonElement.class)), times(1));
            f.web.verify(() -> WebServices.push(eq(f.playerId), anyString(), anyString(), any(JsonElement.class)), never());
            when(f.plugin.status()).thenReturn(new TreesStatus("1.24.0-26.2", defaults(), 6, 2, 1, 3, 4, 6, 7, 99, 120, 400, 38, 0, List.of()));
            tick.run();
            f.web.verify(() -> WebServices.push(eq(f.adminId), eq("trees"), eq("status"), any(JsonElement.class)), times(2));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final CustomTreesPlugin plugin = TestCore.plugin();
        final MockedStatic<WebServices> web = mockStatic(WebServices.class);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final UUID adminId = UUID.randomUUID();
        final UUID playerId = UUID.randomUUID();
        final Player admin = mock(Player.class);
        final Player player = mock(Player.class);
        final AtomicReference<Runnable> timer = new AtomicReference<>();
        final WebModule module;

        Fixture() {
            TreeSettings settings = defaults();
            when(plugin.status()).thenReturn(sample(settings));
            when(admin.getUniqueId()).thenReturn(adminId);
            when(admin.hasPermission("customtrees.admin")).thenReturn(true);
            when(player.getUniqueId()).thenReturn(playerId);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> {
                timer.set(call.getArgument(1));
                return mock(BukkitTask.class);
            });
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getPlayer(adminId)).thenReturn(admin);
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(call -> List.of(admin, player));
            new TreesWeb(plugin, LangFilesTest.bundle(), () -> settings).register();
            ArgumentCaptor<WebModule> captor = ArgumentCaptor.forClass(WebModule.class);
            web.verify(() -> WebServices.register(eq(plugin), captor.capture()));
            module = captor.getValue();
        }

        WebRoute route(WebMethod method, String path) {
            return module.routes().stream().filter(r -> r.method() == method && r.path().equals(path)).findFirst()
                    .orElseThrow(() -> new AssertionError("no route " + method + " " + path));
        }

        @SuppressWarnings("unchecked")
        JsonObject call(WebRoute route) throws Exception {
            WebRequest request = mock(WebRequest.class);
            when(request.playerId()).thenReturn(adminId);
            when(request.playerName()).thenReturn("Admin");
            when(request.language()).thenReturn("en");
            when(request.sync(any(Callable.class))).thenAnswer(call -> ((Callable<Object>) call.getArgument(0)).call());
            WebResponse response = route.handler().handle(request);
            return JsonParser.parseString(response.bodyText()).getAsJsonObject();
        }

        @Override
        public void close() {
            web.close();
            bukkit.close();
        }
    }
}
