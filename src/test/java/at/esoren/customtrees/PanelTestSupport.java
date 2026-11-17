package at.esoren.customtrees;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.PanelSession;
import Vystorm.vystorm_core.panel.Ui;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

final class PanelTestSupport {
    static final String[] LANGUAGES = {"de", "en", "fr", "it", "pt_br", "ru", "zh_cn"};

    static Lang lang() {
        Map<String, String> bundles = new LinkedHashMap<>();
        for (String language : LANGUAGES) bundles.put(language, LangFilesTest.resource("lang/" + language + ".yml"));
        return Lang.create("CustomTrees", bundles);
    }

    static PanelContext context(Player player) { return context(player, "en"); }

    static PanelContext context(Player player, String language) {
        Lang lang = lang();
        return new PanelContext() {
            public Player player() { return player; }
            public String arg() { return ""; }
            public String text(String key, Object... placeholders) { return lang.plain(language, key, placeholders); }
            public Component component(String key, Object... placeholders) { return lang.component(language, key, placeholders); }
            public PanelSession session() { return null; }
        };
    }

    // PanelTesting validates documents but intentionally never invokes handlers. Reach the actual builder callback
    // in these interaction tests, without a server, network session, or copying the production handler logic.
    static Object handler(Panel panel, String id) throws ReflectiveOperationException {
        Ui.Element<?> element = find(panel, id);
        if (element == null) throw new AssertionError("Missing element " + id);
        Field handler = Ui.Element.class.getDeclaredField("handler");
        handler.setAccessible(true);
        return handler.get(element);
    }

    private static Ui.Element<?> find(Object value, String id) throws ReflectiveOperationException {
        if (value == null) return null;
        if (value instanceof Ui.Element<?> element) {
            Field field = Ui.Element.class.getDeclaredField("id");
            field.setAccessible(true);
            if (id.equals(field.get(element))) return element;
        }
        if (value instanceof Iterable<?> values) {
            for (Object child : values) { var hit = find(child, id); if (hit != null) return hit; }
        } else if (value instanceof Object[] values) {
            for (Object child : values) { var hit = find(child, id); if (hit != null) return hit; }
        } else if (value instanceof Panel || value instanceof Ui.Element<?>) {
            for (Class<?> type = value.getClass(); type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    var hit = find(field.get(value), id);
                    if (hit != null) return hit;
                }
            }
        }
        return null;
    }
}
