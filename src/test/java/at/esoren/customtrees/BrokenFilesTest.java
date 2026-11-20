package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A config.yml or data file with a YAML error is never saved over. */
class BrokenFilesTest {
    static final String BROKEN = "settings:\n  custom-player-grown-trees: true\n  replacement-chance: 0.5\n broken here\ntrees:\n  enabled: true\n";

    @TempDir Path folder;
    final List<LogRecord> logged = new ArrayList<>();

    CustomTreesPlugin plugin() throws Exception {
        CustomTreesPlugin plugin = mock(CustomTreesPlugin.class, CALLS_REAL_METHODS);
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logged.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        doReturn(folder.toFile()).when(plugin).getDataFolder();
        doReturn(logger).when(plugin).getLogger();
        doReturn(new YamlConfiguration()).when(plugin).getConfig();
        doNothing().when(plugin).reloadConfig();
        doNothing().when(plugin).saveConfig();
        Lang lang = mock(Lang.class);
        when(lang.language(null)).thenReturn("en");
        when(lang.plain(eq("en"), eq("config-error"), any(Object[].class))).thenAnswer(call -> "config.yml has a YAML error at " + call.getArgument(3));
        Field field = CustomTreesPlugin.class.getDeclaredField("lang");
        field.setAccessible(true);
        field.set(plugin, lang);
        return plugin;
    }

    @Test void givenBrokenConfig_whenReloaded_thenTheFileStaysTheSettingsStayAndTheAnswerNamesLineAndColumn() throws Exception {
        Path file = folder.resolve("config.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);
        CustomTreesPlugin plugin = plugin();
        String error = plugin.reload("en");
        assertNotNull(error, "the reload is refused");
        assertTrue(error.contains("line 4, column"), error);
        verify(plugin, never()).reloadConfig();
        verify(plugin, never()).saveConfig();
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(1, logged.stream().filter(r -> r.getLevel() == Level.SEVERE).count());
        // start: the same check, Bukkit then serves the jar defaults; nothing is written
        assertNotNull(YamlCheck.readConfig(plugin, false));
        assertArrayEquals(before, Files.readAllBytes(file));
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn"))
            assertTrue(YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml")).getString("config-error", "").contains("{problem}"), code);
    }

    @Test void givenBrokenConfig_whenTheSettingsMenuSaves_thenTheFileIsNotOverwritten() throws Exception {
        Path file = folder.resolve("config.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        CustomTreesPlugin plugin = plugin();
        plugin.saveCheckedConfig();
        verify(plugin, never()).saveConfig();
        assertEquals(BROKEN, Files.readString(file, StandardCharsets.UTF_8));
        Files.writeString(file, "trees:\n  enabled: true\n", StandardCharsets.UTF_8);
        plugin.saveCheckedConfig();
        verify(plugin).saveConfig();
    }

    @Test void givenBrokenRegrowthFile_whenLoaded_thenItIsCopiedAsideBeforeItCanBeWritten() throws Exception {
        Path file = folder.resolve("forest-regrowth.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        assertEquals(List.of(), ForestRegrowth.load(file));
        try (Stream<Path> files = Files.list(folder)) {
            Path copy = files.filter(p -> p.getFileName().toString().startsWith("forest-regrowth.yml.broken-")).findFirst().orElseThrow(() -> new AssertionError("no copy"));
            assertEquals(BROKEN, Files.readString(copy, StandardCharsets.UTF_8));
        }
    }

    @Test void givenBrokenYaml_whenDescribed_thenLineColumnAndProblemAreNamed() throws Exception {
        Path file = folder.resolve("x.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        assertTrue(YamlCheck.problem(file.toFile()).matches("line 4, column \\d+: .+"), YamlCheck.problem(file.toFile()));
    }
}
