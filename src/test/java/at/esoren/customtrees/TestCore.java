package at.esoren.customtrees;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.loot.LootInterface;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Vystorm Core for tests that run without a server. */
final class TestCore {
    /** Core's loot manager with its default parts. */
    static final LootInterface LOOT = new LootInterface() {};

    private TestCore() {}

    /** A mocked plugin whose {@code core()} is {@link #api()}. */
    static CustomTreesPlugin plugin() {
        CustomTreesPlugin plugin = mock(CustomTreesPlugin.class);
        org.mockito.Mockito.when(plugin.core()).thenReturn(api());
        return plugin;
    }

    /**
     * A hub whose managers hand out Core's default parts ({@code api.compat().platform()}, {@code api.data().files()},
     * {@code api.area().regions()} ...); those delegate to Core's static classes, as on a server. Manager methods that
     * need a running Core answer like a plain mock. Tests stub single managers on top with {@code when(...)}.
     */
    static API api() {
        Map<Class<?>, Object> managers = new ConcurrentHashMap<>();
        return mock(API.class, call -> {
            Class<?> type = call.getMethod().getReturnType();
            if (!type.isInterface()) return RETURNS_DEFAULTS.answer(call);
            return managers.computeIfAbsent(type, manager -> mock(manager, CALLS_REAL_METHODS));
        });
    }
}
