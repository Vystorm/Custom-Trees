package at.esoren.customtrees;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Per-player catalyst controls. Persistent use is recorded only after a committed tree growth. */
final class CatalystUseLimiter {
    enum Catalyst {
        GLOWSTONE("glowstone"), COAL("coal");

        private final String key;

        Catalyst(String key) {
            this.key = key;
        }

        int cooldown(TreeSettings settings) {
            return this == GLOWSTONE ? settings.glowstoneGrowthCooldownSeconds() : settings.coalGrowthCooldownSeconds();
        }

        int dailyLimit(TreeSettings settings) {
            return this == GLOWSTONE ? settings.glowstoneGrowthDailyLimit() : settings.coalGrowthDailyLimit();
        }
    }

    record Reservation(UUID player, Catalyst catalyst, long sequence, long utcDay) {
    }

    private record Key(UUID player, Catalyst catalyst) {
    }

    private record State(long utcDay, int dailyCount, long nextAllowedMillis) {
    }

    private final Clock clock;
    private final Map<Key, List<Reservation>> pending = new HashMap<>();
    private final Map<Catalyst, NamespacedKey[]> keys = new EnumMap<>(Catalyst.class);
    private long nextSequence;

    CatalystUseLimiter() {
        this(Clock.systemUTC());
    }

    CatalystUseLimiter(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        for (Catalyst catalyst : Catalyst.values()) {
            keys.put(catalyst, new NamespacedKey[] {
                    new NamespacedKey("customtrees", catalyst.key + "_day"),
                    new NamespacedKey("customtrees", catalyst.key + "_count"),
                    new NamespacedKey("customtrees", catalyst.key + "_next")
            });
        }
    }

    /** Reserves capacity while a one-tick growth task is pending; the PDC is untouched. */
    Optional<Reservation> reserve(Player player, Catalyst catalyst, TreeSettings settings) {
        long now = clock.millis();
        long day = utcDay(now);
        Key key = new Key(player.getUniqueId(), catalyst);
        State state = read(player.getPersistentDataContainer(), catalyst, day);
        List<Reservation> reservations = pending.computeIfAbsent(key, ignored -> new ArrayList<>());
        int cooldown = catalyst.cooldown(settings);
        int dailyLimit = catalyst.dailyLimit(settings);
        int reservationsToday = (int) reservations.stream().filter(item -> item.utcDay() == state.utcDay()).count();
        if (cooldown > 0) {
            long projectedNext = state.nextAllowedMillis();
            for (Reservation ignored : reservations) {
                projectedNext = Math.max(projectedNext, now) + cooldown * 1_000L;
            }
            if (now < projectedNext) {
                cleanup(key, reservations);
                return Optional.empty();
            }
        }
        if (dailyLimit > 0 && (state.dailyCount() + reservationsToday) >= dailyLimit) {
            cleanup(key, reservations);
            return Optional.empty();
        }
        Reservation reservation = new Reservation(key.player(), catalyst, ++nextSequence, state.utcDay());
        reservations.add(reservation);
        return Optional.of(reservation);
    }

    /** Rechecks against settings that may have changed while the growth was queued. */
    boolean canContinue(Player player, Reservation reservation, TreeSettings current) {
        if (!reservation.player().equals(player.getUniqueId())) return false;
        Key reservationKey = new Key(reservation.player(), reservation.catalyst());
        if (!pending.getOrDefault(reservationKey, List.of()).contains(reservation)) return false;
        long now = clock.millis();
        State state = read(player.getPersistentDataContainer(), reservation.catalyst(), utcDay(now));
        int cooldown = reservation.catalyst().cooldown(current);
        if (cooldown > 0 && now < state.nextAllowedMillis()) return false;
        int dailyLimit = reservation.catalyst().dailyLimit(current);
        if (dailyLimit == 0) return true;
        Key key = new Key(reservation.player(), reservation.catalyst());
        List<Reservation> reservations = pending.getOrDefault(key, List.of());
        long earlierToday = reservations.stream()
                .filter(item -> item.sequence() <= reservation.sequence())
                .count();
        return state.dailyCount() + earlierToday <= dailyLimit;
    }

    /** Removes a reservation and persists this successful use using the current (possibly reloaded) policy. */
    void commit(Player player, Reservation reservation, TreeSettings current) {
        release(reservation);
        if (reservation.catalyst().cooldown(current) == 0 && reservation.catalyst().dailyLimit(current) == 0) return;
        long now = clock.millis();
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (data == null) return;
        State old = read(data, reservation.catalyst(), utcDay(now));
        long day = old.utcDay();
        int count = old.dailyCount() == Integer.MAX_VALUE ? Integer.MAX_VALUE : old.dailyCount() + 1;
        NamespacedKey[] tags = keys.get(reservation.catalyst());
        data.set(tags[0], PersistentDataType.LONG, day);
        data.set(tags[1], PersistentDataType.INTEGER, count);
        long cooldownMillis = reservation.catalyst().cooldown(current) * 1_000L;
        if (cooldownMillis > 0) {
            long from = Math.max(now, old.nextAllowedMillis());
            data.set(tags[2], PersistentDataType.LONG, saturatingAdd(from, cooldownMillis));
        }
    }

    void release(Reservation reservation) {
        Key key = new Key(reservation.player(), reservation.catalyst());
        List<Reservation> reservations = pending.get(key);
        if (reservations == null) return;
        reservations.removeIf(item -> item.sequence() == reservation.sequence());
        cleanup(key, reservations);
    }

    void clearPending() {
        pending.clear();
    }

    private State read(PersistentDataContainer data, Catalyst catalyst, long today) {
        if (data == null) return new State(today, 0, 0);
        NamespacedKey[] tags = keys.get(catalyst);
        long storedDay = data.getOrDefault(tags[0], PersistentDataType.LONG, Long.MIN_VALUE);
        int storedCount = data.getOrDefault(tags[1], PersistentDataType.INTEGER, 0);
        long storedNext = data.getOrDefault(tags[2], PersistentDataType.LONG, 0L);
        // A backwards system-clock adjustment must never erase a later day or cooldown.
        long effectiveDay = Math.max(today, storedDay);
        return new State(effectiveDay, effectiveDay == storedDay ? Math.max(0, storedCount) : 0,
                Math.max(0, storedNext));
    }

    private static long utcDay(long epochMillis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC).toEpochDay();
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private void cleanup(Key key, List<Reservation> reservations) {
        if (reservations.isEmpty()) pending.remove(key);
    }
}
