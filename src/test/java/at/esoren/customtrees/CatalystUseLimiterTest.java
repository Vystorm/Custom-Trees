package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

class CatalystUseLimiterTest {
    @Test
    void dailyCapReservesAttemptsAndOnlyCountsSuccessfulCommitAcrossUtcDays() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-13T23:59:00Z"));
        CatalystUseLimiter limiter = new CatalystUseLimiter(clock);
        TestPlayer fixture = player(UUID.randomUUID());
        Player player = fixture.player();
        TreeSettings settings = settings(0, 1, 0, 0);

        CatalystUseLimiter.Reservation failed = limiter.reserve(player, CatalystUseLimiter.Catalyst.GLOWSTONE,
                settings).orElseThrow();
        assertTrue(fixture.stored().isEmpty(), "a reservation is not persisted as a successful use");
        assertTrue(limiter.reserve(player, CatalystUseLimiter.Catalyst.GLOWSTONE, settings).isEmpty(),
                "in-flight attempts reserve daily capacity");
        limiter.release(failed);
        CatalystUseLimiter.Reservation successful = limiter.reserve(player,
                CatalystUseLimiter.Catalyst.GLOWSTONE, settings).orElseThrow();
        assertTrue(limiter.canContinue(player, successful, settings));
        limiter.commit(player, successful, settings);
        assertEquals(1, readStored(fixture, "glowstone_count"));
        assertTrue(limiter.reserve(player, CatalystUseLimiter.Catalyst.GLOWSTONE, settings).isEmpty());

        clock.set(Instant.parse("2026-10-14T00:00:01Z"));
        CatalystUseLimiter.Reservation nextDay = limiter.reserve(player,
                CatalystUseLimiter.Catalyst.GLOWSTONE, settings).orElseThrow();
        limiter.commit(player, nextDay, settings);
        assertEquals(1, readStored(fixture, "glowstone_count"));

        clock.set(Instant.parse("2026-10-13T23:59:59Z"));
        assertTrue(limiter.reserve(player, CatalystUseLimiter.Catalyst.GLOWSTONE, settings).isEmpty(),
                "a backwards clock adjustment cannot reset the later UTC-day count");
    }

    @Test
    void catalystCooldownPersistsAcrossMidnightAndCatalystsAndPlayersAreSeparate() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-13T23:30:00Z"));
        CatalystUseLimiter limiter = new CatalystUseLimiter(clock);
        Player first = player(UUID.randomUUID()).player();
        Player second = player(UUID.randomUUID()).player();
        TreeSettings settings = settings(0, 0, 3_600, 0);

        CatalystUseLimiter.Reservation firstUse = limiter.reserve(first, CatalystUseLimiter.Catalyst.COAL,
                settings).orElseThrow();
        limiter.commit(first, firstUse, settings);
        assertTrue(limiter.reserve(first, CatalystUseLimiter.Catalyst.COAL, settings).isEmpty());
        assertTrue(limiter.reserve(first, CatalystUseLimiter.Catalyst.GLOWSTONE, settings).isPresent());
        assertTrue(limiter.reserve(second, CatalystUseLimiter.Catalyst.COAL, settings).isPresent());

        clock.set(Instant.parse("2026-10-14T00:01:00Z"));
        assertTrue(limiter.reserve(first, CatalystUseLimiter.Catalyst.COAL, settings).isEmpty(),
                "daily rollover does not clear a longer active cooldown");
        clock.set(Instant.parse("2026-10-14T00:31:00Z"));
        assertTrue(limiter.reserve(first, CatalystUseLimiter.Catalyst.COAL, settings).isPresent());
    }

    @Test
    void queuedAttemptsAreRecheckedInOrderAfterDailyLimitIsTightened() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-13T12:00:00Z"));
        CatalystUseLimiter limiter = new CatalystUseLimiter(clock);
        TestPlayer fixture = player(UUID.randomUUID());
        TreeSettings original = settings(0, 2, 0, 0);
        CatalystUseLimiter.Reservation first = limiter.reserve(fixture.player(),
                CatalystUseLimiter.Catalyst.GLOWSTONE, original).orElseThrow();
        CatalystUseLimiter.Reservation second = limiter.reserve(fixture.player(),
                CatalystUseLimiter.Catalyst.GLOWSTONE, original).orElseThrow();
        TreeSettings tightened = settings(0, 1, 0, 0);

        assertTrue(limiter.canContinue(fixture.player(), first, tightened));
        assertFalse(limiter.canContinue(fixture.player(), second, tightened));
        limiter.commit(fixture.player(), first, tightened);
        assertFalse(limiter.canContinue(fixture.player(), second, tightened));
    }

    @Test
    void pendingAttemptsAcrossMidnightStillReserveTheCurrentDayAndReleasedAttemptsCannotCommit() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-13T23:59:59Z"));
        CatalystUseLimiter limiter = new CatalystUseLimiter(clock);
        Player player = player(UUID.randomUUID()).player();
        TreeSettings original = settings(0, 2, 0, 0);
        var first = limiter.reserve(player, CatalystUseLimiter.Catalyst.GLOWSTONE, original).orElseThrow();
        var second = limiter.reserve(player, CatalystUseLimiter.Catalyst.GLOWSTONE, original).orElseThrow();
        clock.set(Instant.parse("2026-10-14T00:00:01Z"));
        TreeSettings tightened = settings(0, 1, 0, 0);
        assertTrue(limiter.canContinue(player, first, tightened));
        limiter.commit(player, first, tightened);
        assertFalse(limiter.canContinue(player, second, tightened));
        limiter.release(second);
        assertFalse(limiter.canContinue(player, second, original));
    }

    private static TreeSettings settings(int glowCooldown, int glowDaily, int coalCooldown, int coalDaily) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("settings.glowstone-growth-cooldown-seconds", glowCooldown);
        yaml.set("settings.glowstone-growth-daily-limit", glowDaily);
        yaml.set("settings.coal-growth-cooldown-seconds", coalCooldown);
        yaml.set("settings.coal-growth-daily-limit", coalDaily);
        return TreeSettings.load(yaml);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TestPlayer player(UUID id) {
        Player player = mock(Player.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        Map<NamespacedKey, Object> stored = new HashMap<>();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getPersistentDataContainer()).thenReturn(data);
        when(data.getOrDefault(any(NamespacedKey.class), any(PersistentDataType.class), any()))
                .thenAnswer(call -> stored.getOrDefault(call.getArgument(0), call.getArgument(2)));
        doAnswer(call -> {
            stored.put(call.getArgument(0), call.getArgument(2));
            return null;
        }).when(data).set(any(NamespacedKey.class), any(PersistentDataType.class), any());
        return new TestPlayer(player, stored);
    }

    private static Object readStored(TestPlayer fixture, String key) {
        Map<NamespacedKey, Object> stored = fixture.stored();
        return stored.entrySet().stream().filter(entry -> entry.getKey().getKey().equals(key))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private record TestPlayer(Player player, Map<NamespacedKey, Object> stored) { }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant value) {
            now = value;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
