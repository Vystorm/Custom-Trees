package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.lang.Lang;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class LangFilesTest {
    private static final Set<String> VALIDATION_KEYS = Set.of(
            "validation.replacement-chance", "validation.giant-heights", "validation.giant-canopy-radius",
            "validation.colossal-heights", "validation.colossal-canopy-radius", "validation.glowstone-xp-cost",
            "validation.generated-tree-scan-depth", "validation.glowstone-leaves-per-block",
            "validation.glowstone-max-blocks", "validation.glowstone-dark-light-level",
            "validation.new-chunk-post-process-delay-ticks", "validation.catalyst-cooldown",
            "validation.catalyst-daily-limit");
    /** Keys passed to lang.send/component/actionBar/ref/plain in the sources ("..." literal arguments). */
    private static final Pattern USED_KEY = Pattern.compile(
            "(?:send|component|actionBar|ref|plain)\\((?:[a-zA-Z]+, )?\"([a-z0-9.-]+)\"");

    static String resource(String name) {
        try (InputStream in = LangFilesTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name + " is missing from the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    /** The bundled en/de/zh_cn files as a standalone bundle (no server needed). */
    static Lang bundle() {
        return Lang.create("CustomTrees", Map.of("en", resource("lang/en.yml"), "de", resource("lang/de.yml"),
                "zh_cn", resource("lang/zh_cn.yml")));
    }

    private static final Pattern TOKEN = Pattern.compile("\\{[^}]+}|<[^>]+>");

    private static YamlConfiguration yaml(String file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(resource(file));
        } catch (Exception failure) {
            throw new AssertionError(file + " is not valid YAML", failure);
        }
        return yaml;
    }

    /** Placeholders ({name}) and MiniMessage tags of a text, sorted. */
    private static java.util.List<String> tokens(String text) {
        java.util.List<String> found = new java.util.ArrayList<>();
        Matcher matcher = TOKEN.matcher(text == null ? "" : text);
        while (matcher.find()) found.add(matcher.group());
        java.util.Collections.sort(found);
        return found;
    }

    @Test
    void chineseDefinesTheSameKeysPlaceholdersAndTags() {
        Set<String> en = keys("lang/en.yml");
        assertEquals(en, keys("lang/zh_cn.yml"));
        YamlConfiguration english = yaml("lang/en.yml");
        YamlConfiguration chinese = yaml("lang/zh_cn.yml");
        for (String key : en) {
            String text = chinese.getString(key);
            assertEquals(tokens(english.getString(key)), tokens(text), key);
            assertFalse(text.contains("<arg"), key + " contains a raw <arg> tag");
        }
        assertEquals("发光树：你需要 100 点经验。",
                bundle().plain("zh_cn", "growth.glowstone-missing-xp", "xp", 100));
    }

    private static Set<String> keys(String file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(resource(file));
        } catch (Exception failure) {
            throw new AssertionError(file + " is not valid YAML", failure);
        }
        Set<String> keys = new TreeSet<>();
        for (String key : yaml.getKeys(true)) {
            if (!yaml.isConfigurationSection(key)) keys.add(key);
        }
        return keys;
    }

    @Test
    void englishAndGermanDefineTheSameKeys() {
        Set<String> en = keys("lang/en.yml");
        Set<String> de = keys("lang/de.yml");
        assertFalse(en.isEmpty());
        assertEquals(en, de);
    }

    @Test
    void everySettingAndEveryKeyUsedInTheCodeExists() throws IOException {
        Set<String> en = keys("lang/en.yml");
        assertTrue(en.containsAll(VALIDATION_KEYS));
        for (String name : TreeSettingsEditor.SETTING_NAMES) {
            assertTrue(en.contains("settings." + name + ".label"), name);
            assertTrue(en.contains("settings." + name + ".description"), name);
        }
        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = USED_KEY.matcher(Files.readString(file));
                while (matcher.find()) if (!matcher.group(1).endsWith(".")) used.add(matcher.group(1));
            }
        }
        assertTrue(used.size() >= 10, "key scan found too little: " + used);
        used.removeAll(en);
        assertEquals(Set.of(), used, "keys used in code but missing in en.yml");
    }

    @Test
    void placeholdersAreFilledInBothLanguages() {
        Lang lang = bundle();
        assertEquals("Luminous tree: You need 100 XP points.",
                lang.plain("en", "growth.glowstone-missing-xp", "xp", 100));
        assertEquals("Leuchtbaum: Du brauchst 100 XP-Punkte.",
                lang.plain("de", "growth.glowstone-missing-xp", "xp", 100));
        assertEquals("CustomTrees: Direkt über dem Stamm ist nicht genug Platz.", lang.plain("de", "growth.no-room"));
        assertEquals("Settings", lang.plain("fr", "action.settings"));
    }

    @Test
    void englishTutorialCoversEveryGermanPage() {
        YamlConfiguration de = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(resource("tutorial.yml")));
        YamlConfiguration en = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(resource("tutorial_en.yml")));
        assertEquals(de.getMapList("pages").size(), en.getMapList("pages").size());
        assertEquals(de.getString("permission"), en.getString("permission"));
        for (int i = 0; i < de.getMapList("pages").size(); i++) {
            assertEquals(de.getMapList("pages").get(i).get("permission"), en.getMapList("pages").get(i).get("permission"));
        }
    }
}
