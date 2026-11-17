package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.commands.CommandRegistration;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.commands.CommandTesting;
import Vystorm.vystorm_core.lang.Lang;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** /customtrees through Core's command tree with the real language files: names, routing, help per language. */
class CustomTreesCommandsTest {
    private static final String[] ALL = {"customtrees.admin", "customtrees.grow", "customtrees.garden", "customtrees.plant", "customtrees.herbarium"};
    private final List<String> chat = new ArrayList<>();
    private final CustomTreesPlugin plugin = TestCore.plugin();

    private CommandRegistration registration() {
        Plugin owner = mock(Plugin.class);
        when(owner.isEnabled()).thenReturn(true);
        when(owner.getName()).thenReturn("CustomTrees");
        Lang lang = LangFilesTest.bundle();
        return CommandTesting.detached(owner, lang, CustomTreesPlugin.commandSpec(plugin));
    }

    private Player player(Locale locale, String... permissions) {
        Player p = mock(Player.class);
        when(p.locale()).thenReturn(locale);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.hasPermission(anyString())).thenAnswer(i -> List.of(permissions).contains((String) i.getArgument(0)));
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(p).sendMessage(any(Component.class));
        return p;
    }

    private ConsoleCommandSender console() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(console).sendMessage(any(Component.class));
        return console;
    }

    private void run(CommandSender sender, String label, String... args) { registration().execute(sender, label, args); }

    @Test void nextTaskRoutesLocalizedCommandsAndFiltersPermissions() {
        Player german = player(Locale.GERMANY, "customtrees.herbarium");
        run(german, "baumgarten", "weiter", "cherry");
        verify(plugin).commandNext(german, "cherry");
        Player chinese = player(Locale.SIMPLIFIED_CHINESE, "customtrees.herbarium");
        run(chinese, "customtrees", "下一步");
        verify(plugin).commandNext(chinese, null);
        assertEquals(List.of("cherry"), registration().suggest(german, "customtrees", new String[]{"next", "ch"}));
        run(player(Locale.US), "customtrees", "next");
        run(console(), "customtrees", "next");
        run(german, "customtrees", "next", "cherry", "extra");
        verify(plugin, times(2)).commandNext(any(), any());
    }

    @Test
    void hedgeArgumentsRouteAndCompleteThroughCore() {
        Player p = player(Locale.US, "customtrees.plant");
        run(p, "customtrees", "plant", "hedge12");
        verify(plugin).commandPlant(p, "hedge12");
        assertEquals(List.of("hedge4", "hedge8", "hedge16"),
                registration().suggest(p, "customtrees", new String[]{"plant", "hedge"}));
        assertEquals(List.of(), registration().suggest(player(Locale.US), "customtrees", new String[]{"plant", "hedge"}));
    }

    @Test
    void givenLanguages_whenCompleting_thenSubcommandsInPlayersLanguage() {
        CommandRegistration r = registration();
        String[] none = {""};
        assertEquals(List.of("guide", "sapling", "prune", "plant", "herbarium", "next", "status", "reload", "help"),
                r.suggest(player(Locale.US, ALL), "customtrees", none));
        assertEquals(List.of("ratgeber", "setzling", "zurueckschneiden", "pflanzen", "herbarium", "weiter", "status", "neuladen", "hilfe"),
                r.suggest(player(Locale.GERMANY, ALL), "baumgarten", none));
        assertEquals(List.of("指南", "树苗", "修剪", "种植", "植物图鉴", "下一步", "帮助"),
                r.suggest(player(Locale.SIMPLIFIED_CHINESE, "customtrees.grow", "customtrees.garden", "customtrees.plant", "customtrees.herbarium"), "自定义树木", none));
        assertEquals(List.of("guide", "help"), r.suggest(player(Locale.US), "customtrees", none), "without rights only the guide");
        assertEquals(List.of("web"), r.suggest(player(Locale.US), "customtrees", new String[]{"we"}), "the old web alias is still offered");
    }

    @Test
    void givenDeeperLevels_whenCompleting_thenLocalizedKeywordsAndPermissionFiltered() {
        CommandRegistration r = registration();
        assertEquals(List.of("guide", "species", "admin"), r.suggest(player(Locale.US, ALL), "customtrees", new String[]{"WEB", ""}));
        assertEquals(List.of("ratgeber", "arten"), r.suggest(player(Locale.GERMANY), "customtrees", new String[]{"guide", ""}));
        assertEquals(List.of("windswept"), r.suggest(player(Locale.US, ALL), "customtrees", new String[]{"sapling", "w"}));
        assertEquals(List.of("giant"), r.suggest(player(Locale.US, ALL), "customtrees", new String[]{"sapling", "forest", "gi"}));
        assertEquals(List.of(), r.suggest(player(Locale.US), "customtrees", new String[]{"sapling", ""}));
        assertEquals(List.of("10x10"), r.suggest(player(Locale.US, "customtrees.plant"), "customtrees", new String[]{"plant", "1"}));
        assertEquals(List.of("aus"), r.suggest(player(Locale.GERMANY, "customtrees.plant"), "customtrees", new String[]{"pflanzen", "a"}));
        assertEquals(List.of("top"), r.suggest(player(Locale.US, "customtrees.herbarium"), "customtrees", new String[]{"herbarium", "t"}));
        assertEquals(List.of("排行榜"), r.suggest(player(Locale.SIMPLIFIED_CHINESE, "customtrees.herbarium"), "customtrees", new String[]{"植物图鉴", ""}));
    }

    @Test
    void givenOldEnglishCommands_whenRun_thenSameHandlersWithSameArguments() {
        Player p = player(Locale.US, ALL);
        run(p, "customtrees");
        verify(plugin).commandGuide(p, null);
        run(p, "customtrees", "guide");
        run(p, "customtrees", "web");
        verify(plugin, times(3)).commandGuide(p, null);
        run(p, "customtrees", "guide", "species");
        run(p, "customtrees", "WEB", "Species");
        verify(plugin, times(2)).commandGuide(p, "species");
        run(p, "customtrees", "web", "admin");
        verify(plugin).commandGuide(p, "admin");
        run(p, "customtrees", "guide", "bogus");
        verify(plugin).commandGuide(p, "bogus");
        run(p, "customtrees", "sapling", "forest", "giant");
        verify(plugin).commandSapling(p, "forest", "giant");
        run(p, "customtrees", "sapling");
        verify(plugin).commandSapling(p, null, null);
        run(p, "customtrees", "prune");
        verify(plugin).commandPrune(p);
        run(p, "customtrees", "plant");
        verify(plugin).commandPlant(p, null);
        run(p, "customtrees", "plant", "3x3");
        verify(plugin).commandPlant(p, "3x3");
        run(p, "customtrees", "plant", "off");
        verify(plugin).commandPlant(p, "off");
        run(p, "customtrees", "herbarium");
        verify(plugin).commandHerbarium(p, null);
        run(p, "customtrees", "herbarium", "top");
        verify(plugin).commandHerbarium(p, "top");
        run(p, "customtrees", "herbarium", "Steve");
        verify(plugin).commandHerbarium(p, "Steve");
        run(p, "customtrees", "status");
        verify(plugin).commandStatus(p);
        run(p, "customtrees", "reload");
        verify(plugin).commandReload(p);
    }

    @Test
    void givenLocalizedNames_whenRun_thenEnglishKeysReachTheHandlers() {
        Player german = player(Locale.GERMANY, ALL);
        run(german, "baumgarten", "pflanzen", "aus");
        verify(plugin).commandPlant(german, "off");
        run(german, "baumgarten", "pflanzen", "status");
        verify(plugin).commandPlant(german, "status");
        run(german, "baumgarten", "ratgeber", "arten");
        verify(plugin).commandGuide(german, "species");
        run(german, "baumgarten", "zurückschneiden");
        verify(plugin).commandPrune(german);
        Player chinese = player(Locale.SIMPLIFIED_CHINESE, ALL);
        run(chinese, "自定义树木", "植物图鉴", "排行榜");
        verify(plugin).commandHerbarium(chinese, "top");
        run(chinese, "customtrees", "plant", "状态");
        verify(plugin).commandPlant(chinese, "status");
        run(chinese, "customtrees", "树苗", "forest", "giant");
        verify(plugin).commandSapling(chinese, "forest", "giant");
    }

    @Test
    void givenConsole_whenRun_thenConsoleCommandsWorkAndPlayerOnlyRefused() {
        ConsoleCommandSender console = console();
        run(console, "customtrees");
        verify(plugin).commandGuide(console, null);
        run(console, "customtrees", "herbarium", "Steve");
        verify(plugin).commandHerbarium(console, "Steve");
        run(console, "customtrees", "reload");
        verify(plugin).commandReload(console);
        run(console, "customtrees", "prune");
        verify(plugin, never()).commandPrune(any());
        assertEquals("Only players can do that.", chat.get(0));
    }

    @Test
    void givenMalformedPrune_whenRun_thenNothingRuns() {
        Player p = player(Locale.US, ALL);
        run(p, "customtrees", "prune", "forest", "giant");
        verify(plugin, never()).commandPrune(any());
        run(player(Locale.US), "customtrees", "status");
        verify(plugin, never()).commandStatus(any());
    }

    @Test
    void givenHelp_whenGerman_thenLocalizedUsageAndDescriptions() {
        run(player(Locale.GERMANY, "customtrees.plant"), "baumgarten", "hilfe");
        String all = String.join("\n", chat);
        assertTrue(all.contains("/baumgarten ratgeber [ratgeber|arten|admin] – Baum-Ratgeber öffnen"), all);
        assertTrue(all.contains("/baumgarten pflanzen [2x2|3x3|5x5|10x10|hedge4..hedge32|aus] – Pflanzhilfe"), all);
        assertFalse(all.contains("neuladen"), all);
    }

    @Test
    void givenRootAliases_whenRead_thenGermanAndChinese() {
        Lang lang = registration().lang();
        assertEquals("baumgarten", lang.raw("de", "commands.customtrees.aliases"));
        assertEquals("自定义树木", lang.raw("zh_cn", "commands.customtrees.aliases"));
        assertEquals(List.of(), registration().fixedAliases());
    }

    @Test
    void givenTree_whenChecked_thenNoNameConflicts() {
        assertEquals(List.of(), registration().conflicts());
    }

    @Test
    void givenLanguageFiles_whenCompared_thenSameCommandKeysAndADescriptionForEveryNode() throws Exception {
        Set<String> en = commandKeys("en");
        assertEquals(en, commandKeys("de"));
        assertEquals(en, commandKeys("zh_cn"));
        List<String> paths = new ArrayList<>();
        collect(CustomTreesPlugin.commandSpec(null), paths);
        for (String code : List.of("en", "de", "zh_cn")) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(LangFilesTest.resource("lang/" + code + ".yml"));
            for (String path : paths) assertFalse(yaml.getString(path + ".description", "").isBlank(), code + ": " + path);
        }
    }

    private static void collect(CommandSpec node, List<String> out) {
        out.add(node.langPath());
        for (CommandSpec child : node.children().values()) collect(child, out);
    }

    private static Set<String> commandKeys(String code) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(LangFilesTest.resource("lang/" + code + ".yml"));
        Set<String> keys = new TreeSet<>();
        for (String key : yaml.getConfigurationSection("commands").getKeys(true)) keys.add(key);
        return keys;
    }
}
