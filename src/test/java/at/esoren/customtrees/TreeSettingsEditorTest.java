package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._uiMethods.settings.Setting;
import Vystorm.vystorm_core._uiMethods.settings.SettingScope;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

class TreeSettingsEditorTest {
    private final YamlConfiguration yaml = bundled();
    private final AtomicInteger saves = new AtomicInteger();
    private final AtomicReference<TreeSettings> active = new AtomicReference<>();
    private final TreeSettingsEditor editor = new TreeSettingsEditor(() -> yaml, saves::incrementAndGet, active::set,
            LangFilesTest.bundle());

    @Test
    void sectionIsAdminOnlyServerSectionOverSettingsKeys() {
        SettingsSection section = editor.section();
        assertEquals("server", section.id());
        assertEquals(SettingScope.SERVER, section.scope());
        assertEquals("customtrees.admin", section.permission());
        assertTrue(section.settings().stream().allMatch(s -> s.id().startsWith("settings.")));
        assertTrue(section.settings().size() <= SettingsSection.MAX_SETTINGS);
        assertTrue(section.settings().stream().noneMatch(s -> s.id().contains("enabled-worlds")));
    }

    @Test
    void savedValuesApplyImmediately() {
        assertNull(editor.load());
        SettingsSection section = editor.section();
        Player admin = mock(Player.class);
        setting(section, "settings.giant-max-height").setter().apply(admin, 60.0);
        setting(section, "settings.coal-growth").setter().apply(admin, false);
        section.afterSave().accept(admin);
        assertEquals(1, saves.get());
        assertEquals(60, active.get().giantMaxHeight());
        assertFalse(active.get().coalGrowth());
        assertEquals("CustomTrees settings applied.", sentText(admin));
    }

    @Test
    void invalidCombinationIsRolledBack() {
        assertNull(editor.load());
        TreeSettings before = active.get();
        SettingsSection section = editor.section();
        Player admin = mock(Player.class);
        setting(section, "settings.giant-min-height").setter().apply(admin, 90.0);
        section.afterSave().accept(admin);
        assertSame(before, active.get());
        assertEquals(36, yaml.getInt("settings.giant-min-height"));
        assertEquals(2, saves.get());
        assertTrue(sentText(admin).startsWith("Invalid combination: Giant heights"), sentText(admin));
        assertTrue(sentText(admin).endsWith("the previous values were restored."), sentText(admin));
    }

    @Test
    void validationErrorsUseRequestedLanguage() {
        yaml.set("settings.replacement-chance", 1.5);
        assertEquals("replacement-chance must be between 0 and 1.", editor.load("en"));
        assertEquals("replacement-chance muss zwischen 0 und 1 liegen.", editor.load("de"));
    }

    @Test
    void invalidBiomeProfilesNeverReplaceTheActiveSettingsSnapshot() {
        AtomicReference<ActiveTreeSettings> activePair = new AtomicReference<>();
        TreeSettingsEditor paired = new TreeSettingsEditor(() -> yaml, saves::incrementAndGet,
                (settings, profiles) -> activePair.set(new ActiveTreeSettings(settings, profiles)),
                LangFilesTest.bundle(), Player::sendMessage);
        assertNull(paired.load());
        ActiveTreeSettings before = activePair.get();
        yaml.set("biome-profiles.meadow.biomes", java.util.List.of("minecraft:plains"));
        yaml.set("biome-profiles.meadow.height-scale", "large");
        assertEquals("Biome profile meadow has an invalid numeric value for height-scale.", paired.load("en"));
        assertSame(before, activePair.get());
        Player admin = mock(Player.class);
        paired.afterSave(admin);
        assertSame(before, activePair.get());
        assertFalse(yaml.contains("biome-profiles"), "the rejected profile tree is restored to the last valid config");
    }

    @Test
    void invalidReloadRestoresDetachedNestedCoalCostsAndLists() {
        java.util.List<Object> originalWorlds = new java.util.ArrayList<>();
        yaml.set("settings.enabled-worlds", originalWorlds);
        assertNull(editor.load());
        TreeSettings before = active.get();
        int originalNormalCost = yaml.getInt("settings.coal-costs.normal");

        yaml.set("settings.coal-costs.normal", 7);
        yaml.set("settings.coal-costs.giant", 12);
        originalWorlds.add("mutated-in-place");
        yaml.set("settings.giant-min-height", 90);
        Player admin = mock(Player.class);
        editor.afterSave(admin);

        assertEquals(originalNormalCost, yaml.getInt("settings.coal-costs.normal"));
        assertEquals(1, yaml.getInt("settings.coal-costs.giant"));
        assertEquals(java.util.List.of(), yaml.getStringList("settings.enabled-worlds"));
        assertSame(before, active.get(), "invalid settings must leave the active snapshot untouched");
        assertEquals(1, saves.get());
    }

    @Test
    void everySettingHasLocalizedLabelAndDescription() {
        SettingsSection section = editor.section();
        assertEquals(TreeSettingsEditor.SETTING_NAMES.size(), section.settings().size());
        for (String name : TreeSettingsEditor.SETTING_NAMES) {
            setting(section, "settings." + name);
        }
        assertTrue(section.description().startsWith("{lang:customtrees:"), section.description());
    }

    private static String sentText(Player player) {
        ArgumentCaptor<Component> message = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(message.capture());
        return PlainTextComponentSerializer.plainText().serialize(message.getValue());
    }

    private static Setting setting(SettingsSection section, String id) {
        return section.settings().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    private static YamlConfiguration bundled() {
        try (var stream = TreeSettingsEditorTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
