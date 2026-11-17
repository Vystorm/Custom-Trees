package at.esoren.customtrees;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Percent trigger: remembers trunk cross-sections being chopped. The first chopped log starts a record with the scanned
 * tree; every further log of the same cross-section counts. The tree falls once {@code required} logs are cut.
 * Bounded (oldest records go first) and records expire after a while. Server thread.
 */
final class ChopProgress {
    /** One cross-section being chopped; {@code plan} is the tree scanned at the first chop. */
    static final class Cut {
        final UUID world;
        final Set<Long> slice;
        final Set<Long> chopped = new HashSet<>();
        final int required;
        final TimberScanner.Result plan;
        long expires;

        Cut(UUID world, TimberScanner.Result plan, int required, long expires) {
            this.world = world;
            this.slice = Set.copyOf(plan.slice());
            this.plan = plan;
            this.required = required;
            this.expires = expires;
        }

        int chopped() { return chopped.size(); }
        boolean ready() { return chopped.size() >= required; }
    }

    static final int MAX_RECORDS = 64;
    private final Map<Long, Cut> byPosition = new LinkedHashMap<>();
    private final Map<Cut, Boolean> records = new LinkedHashMap<>();

    /** The record whose cross-section contains this log, or null. Expired records are dropped first. */
    Cut find(UUID world, long position, long now) {
        expire(now);
        Cut cut = byPosition.get(position);
        return cut != null && cut.world.equals(world) ? cut : null;
    }

    /** Counts one chopped log of a known cross-section; returns the record (ready or not). */
    Cut chop(Cut cut, long position, long now, long lifetimeMillis) {
        cut.chopped.add(position);
        cut.expires = now + lifetimeMillis;
        if (cut.ready()) forget(cut);
        return cut;
    }

    /** Starts a record for a freshly scanned tree with the first chopped log. */
    Cut start(UUID world, TimberScanner.Result plan, long firstLog, int percent, long now, long lifetimeMillis) {
        int required = TimberOptions.requiredCuts(plan.slice().size(), percent);
        Cut cut = new Cut(world, plan, required, now + lifetimeMillis);
        for (long p : cut.slice) {
            Cut old = byPosition.get(p);
            if (old != null) forget(old);
        }
        while (records.size() >= MAX_RECORDS) forget(records.keySet().iterator().next());
        records.put(cut, Boolean.TRUE);
        for (long p : cut.slice) byPosition.put(p, cut);
        return chop(cut, firstLog, now, lifetimeMillis);
    }

    void forget(Cut cut) {
        if (records.remove(cut) == null) return;
        for (long p : cut.slice) byPosition.remove(p, cut);
    }

    void expire(long now) {
        for (Iterator<Cut> it = records.keySet().iterator(); it.hasNext(); ) {
            Cut cut = it.next();
            if (cut.expires >= now) continue;
            it.remove();
            for (long p : cut.slice) byPosition.remove(p, cut);
        }
    }

    int size() { return records.size(); }

    void clear() { records.clear(); byPosition.clear(); }
}
